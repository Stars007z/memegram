import uuid
import jwt
import os
from datetime import datetime, timedelta
from sqlalchemy.ext.asyncio import AsyncSession
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.exceptions import InvalidSignature

from app.repositories.device_repo import DeviceRepository
from app.repositories.session_repo import SessionRepository
from app.repositories.invite_repo import InviteRepository
from app.config import settings
from app.database.redis import store_challenge, get_challenge, delete_challenge, RedisClient



class AuthService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.device_repo = DeviceRepository(session)
        self.session_repo = SessionRepository(session)
        self.invite_repo = InviteRepository(session)

    async def register(
        self,
        username: str,
        invite_code: str,
        device_id: str,
        device_name: str,
        identity_key_pub: bytes,
        init_key_pub: bytes,
        credential_data: bytes
    ) -> dict:
        # 1. Проверка инвайта
        invite = await self.invite_repo.get_by_code(invite_code)
        if not invite or invite.is_used or invite.expires_at < datetime.utcnow():
            raise ValueError("Invalid or expired invite code")

        # 2. Генерация идентификаторов
        user_id = uuid.uuid4()
        device_uuid = uuid.uuid4()

        # 3. Создание устройства
        await self.device_repo.create({
            "id": device_uuid,
            "user_id": user_id,
            "device_id": device_id,
            "device_name": device_name,
            "device_type": "primary",
            "is_active": True,
            "identity_key_pub": identity_key_pub,
            "init_key_pub": init_key_pub,
            "credential_data": credential_data,
        })

        # 4. Генерация токенов
        access_token, refresh_token, expires_at = self._generate_tokens(
            user_id=str(user_id),
            device_id=str(device_uuid),
            is_primary=True
        )

        # 5. Создание сессии
        await self.session_repo.create({
            "device_id": device_uuid,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": expires_at,
        })

        # 6. Помечаем инвайт как использованный
        await self.invite_repo.mark_as_used(invite, used_by_user_id=user_id)



        return {
            "user_id": str(user_id),
            "device_id": str(device_uuid),
            "is_primary": True,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def login_init(self, device_id: str) -> dict:
        # 1. Проверяем устройство по UUID в БД
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")

        if not device.is_active:
            raise ValueError("Device is inactive or revoked")

        # 2. Генерируем challenge (32 байта)
        challenge = os.urandom(32)

        # 3. Сохраняем в Redis по строковому device_id (внешний ID устройства)
        await store_challenge(device_id, challenge, settings.CHALLENGE_TTL_SECONDS)

        import base64
        challenge_b64 = base64.b64encode(challenge).decode('utf-8')
        expires_at = int((datetime.utcnow() + timedelta(seconds=settings.CHALLENGE_TTL_SECONDS)).timestamp())

        return {
            "challenge": challenge_b64,
            "expires_at": expires_at,
            "device_id": device_id,
        }

    async def login_complete(
        self,
        device_id: str,
        challenge: str,
        signature: bytes,
        device_name: str = None
    ) -> dict:
        import base64

        # 1. Декодируем challenge
        try:
            challenge_bytes = base64.b64decode(challenge)
        except Exception:
            raise ValueError("Invalid challenge format")

        # 2. Получаем challenge из Redis
        stored_challenge = await get_challenge(device_id)
        if not stored_challenge:
            raise ValueError("Challenge expired or not found. Please restart login.")

        # 3. Сверяем challenge
        if stored_challenge != challenge_bytes:
            raise ValueError("Challenge mismatch")

        # 4. Находим устройство
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")

        if not device.is_active:
            raise ValueError("Device is inactive or revoked")

        # 5. Верифицируем подпись Ed25519
        try:
            public_key = ed25519.Ed25519PublicKey.from_public_bytes(device.identity_key_pub)
            public_key.verify(signature, challenge_bytes)
        except InvalidSignature:
            raise ValueError("Invalid signature. Authentication failed.")
        except Exception as e:
            raise ValueError(f"Signature verification error: {str(e)}")

        # 6. Удаляем challenge (одноразовое использование)
        await delete_challenge(device_id)

        # 7. Обновляем имя и last_seen
        updates = {"last_seen": datetime.utcnow()}
        if device_name:
            updates["device_name"] = device_name
        await self.device_repo.update(device, updates)

        # 8. Генерируем токены
        access_token, refresh_token, expires_at = self._generate_tokens(
            user_id=str(device.user_id),
            device_id=str(device.id),
            is_primary=(device.device_type == "primary")
        )

        # 9. Создаём сессию
        await self.session_repo.create({
            "device_id": device.id,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": expires_at,
        })


        return {
            "user_id": str(device.user_id),
            "device_id": str(device.id),
            "is_primary": (device.device_type == "primary"),
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def logout(self, access_token: str) -> dict:
        # 1. Находим сессию по токену
        session = await self.session_repo.get_by_field("access_token", access_token)
        if not session:
            raise ValueError("Session not found")

        # 2. Проверяем что токен принадлежит этой сессии через JWT
        try:
            payload = jwt.decode(
                access_token,
                settings.JWT_SECRET,
                algorithms=[settings.JWT_ALGORITHM]
            )
            token_device_id = payload.get("device_id")
            if str(session.device_id) != token_device_id:
                raise ValueError("Token device mismatch")
        except jwt.ExpiredSignatureError:
            pass
        except jwt.InvalidTokenError:
            raise ValueError("Invalid access token")

        # 3. Ревоцируем сессию
        await self.session_repo.update(session, {"is_revoked": True, "last_used": datetime.utcnow()})

        redis = await RedisClient.get_instance()
        cache_key = f"session:valid:{access_token}"

        await redis.delete(cache_key)

        return {
            "success": True,
            "message": "Successfully logged out"
        }

    async def create_invite(
            self,
            expires_in_days: int,
            created_by_device_id: str | None = None
    ) -> dict:
        if not 1 <= expires_in_days <= 365:
            raise ValueError("expires_in_days must be between 1 and 365")

        admin_device_uuid = uuid.UUID(created_by_device_id) if created_by_device_id else None

        invite = await self.invite_repo.create_invite(
            expires_in_days=expires_in_days,
            created_by_admin_device_id=admin_device_uuid
        )


        return {
            "code": invite.code,
            "created_at": int(invite.created_at.timestamp()),
            "expires_at": int(invite.expires_at.timestamp()),
            "is_used": invite.is_used,
            "message": f"Invite code created successfully. Valid for {expires_in_days} days."
        }

    def _generate_tokens(self, user_id: str, device_id: str, is_primary: bool) -> tuple[str, str, datetime]:
        expires_at = datetime.utcnow() + timedelta(minutes=60)

        access_payload = {
            "sub": user_id,
            "device_id": device_id,
            "is_primary": is_primary,
            "exp": expires_at,
            "type": "access"
        }
        refresh_payload = {
            **access_payload,
            "type": "refresh",
            "exp": datetime.utcnow() + timedelta(days=7)
        }

        access_token = jwt.encode(access_payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)
        refresh_token = jwt.encode(refresh_payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)

        return access_token, refresh_token, expires_at

    async def validate_token(self, access_token: str) -> dict:
        # 1. Проверить кэш Redis

        redis = await RedisClient.get_instance()
        cache_key = f"session:valid:{access_token}"
        cached = await redis.get(cache_key)
        if cached:
            import json
            return json.loads(cached)

        # 2. Декодировать JWT
        try:
            payload = jwt.decode(
                access_token,
                settings.JWT_SECRET,
                algorithms=[settings.JWT_ALGORITHM]
            )
        except jwt.ExpiredSignatureError:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}
        except jwt.InvalidTokenError:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}

        device_id = payload.get("device_id")
        expires_at = payload.get("exp", 0)

        # 3. Проверить сессию в БД (не отозвана ли)
        session_record = await self.session_repo.get_by_access_token(access_token)
        if not session_record or session_record.is_revoked:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}

        # 4. Получить device_type из устройства
        device = await self.device_repo.get_by_id(session_record.device_id)
        if not device or not device.is_active:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}

        result = {
            "valid": True,
            "user_id": str(device.user_id),
            "device_id": str(device.id),
            "device_type": device.device_type,
            "expires_at": expires_at,
        }

        # 5. Кэшировать в Redis до истечения токена
        import json
        from datetime import datetime
        ttl = max(0, expires_at - int(datetime.utcnow().timestamp()))
        if ttl > 0:
            await redis.setex(cache_key, ttl, json.dumps(result))

        return result
