import uuid
import jwt
import os
from datetime import datetime, timedelta
from sqlalchemy.ext.asyncio import AsyncSession
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.exceptions import InvalidSignature

from app.repositories.device_repo import DeviceRepository
from app.repositories.session_repo import SessionRepository
from app.repositories.invite_repo import InviteRepository
from app.config import settings
from app.database.redis import store_challenge, get_challenge, delete_challenge




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
        """
        Регистрация нового пользователя.
        Возвращает dict с данными для gRPC-ответа.
        """
        # 1. Проверка инвайта
        invite = await self.invite_repo.get_by_code(invite_code)
        if not invite or invite.is_used or invite.expires_at < datetime.utcnow():
            raise ValueError("Invalid or expired invite code")

        # 2. Генерация идентификаторов
        user_id = uuid.uuid4()
        device_uuid = uuid.uuid4()

        # 3. Создание устройства
        new_device = await self.device_repo.create({
            "id": device_uuid,
            "user_id": user_id,
            "device_id": device_id,
            "device_name": device_name,
            "device_type": "primary",  # Первое устройство — всегда primary
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

        # 7. TODO: Вызов User Service для создания профиля

        await self.session.commit()

        return {
            "user_id": str(user_id),
            "device_id": str(device_uuid),
            "is_primary": True,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def login_init(self, device_id: str) -> dict:
        """
        Генерация challenge для аутентификации
        """
        # 1. Проверяем, существует ли устройство
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")

        # 2. Проверяем активность устройства
        if not device.is_active:
            raise ValueError("Device is inactive or revoked")

        # 3. Генерируем криптографически случайный challenge (32 байта)
        challenge = os.urandom(32)

        # 4. Сохраняем в Redis с TTL
        await store_challenge(device_id, challenge, settings.CHALLENGE_TTL_SECONDS)

        # 5. Возвращаем challenge (base64 для передачи по сети)
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
        """
        Верификация подписи и выдача токенов
        """
        import base64

        # 1. Декодируем challenge из base64
        try:
            challenge_bytes = base64.b64decode(challenge)
        except Exception:
            raise ValueError("Invalid challenge format")

        # 2. Получаем сохранённый challenge из Redis
        stored_challenge = await get_challenge(device_id)
        if not stored_challenge:
            raise ValueError("Challenge expired or not found. Please restart login.")

        # 3. Проверяем, что challenge совпадает (защита от подмены)
        if stored_challenge != challenge_bytes:
            raise ValueError("Challenge mismatch")

        # 4. Находим устройство в БД
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")

        # 5. Проверяем активность
        if not device.is_active:
            raise ValueError("Device is inactive or revoked")

        # 6. Верифицируем подпись
        try:
            public_key = ed25519.Ed25519PublicKey.from_public_bytes(device.identity_key_pub)
            public_key.verify(signature, challenge_bytes)
        except InvalidSignature:
            raise ValueError("Invalid signature. Authentication failed.")
        except Exception as e:
            raise ValueError(f"Signature verification error: {str(e)}")

        # 7. Удаляем challenge из Redis (одноразовое использование)
        await delete_challenge(device_id)

        # 8. Обновляем имя устройства, если передано
        if device_name:
            await self.device_repo.update(device, {"device_name": device_name})

        # 9. Обновляем last_seen
        await self.device_repo.update(device, {"last_seen": datetime.utcnow()})

        # 10. Генерируем токены
        access_token, refresh_token, expires_at = self._generate_tokens(
            user_id=str(device.user_id),
            device_id=str(device.id),
            is_primary=(device.device_type == "primary")
        )

        # 11. Создаём новую сессию
        await self.session_repo.create({
            "device_id": device.id,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": expires_at,
        })

        # 12. Commit
        await self.session.commit()

        return {
            "user_id": str(device.user_id),
            "device_id": str(device.id),
            "is_primary": (device.device_type == "primary"),
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def logout(self, access_token: str) -> dict:
        """Завершение сессии (отзыв токена)"""
        try:
            # Декодируем токен без верификации, чтобы получить device_id
            payload = jwt.decode(access_token, options={"verify_signature": False})
            device_uuid = uuid.UUID(payload.get("device_id"))
        except Exception:
            raise ValueError("Invalid access token")

        # Находим сессию
        session = await self.session_repo.get_by_field("access_token", access_token)
        if not session:
            raise ValueError("Session not found")

        # Помечаем как отозванную
        await self.session_repo.update(session, {
            "is_revoked": True,
            "last_used": datetime.utcnow()
        })

        await self.session.commit()

        return {
            "success": True,
            "message": "Successfully logged out"
        }

    async def create_invite(
            self,
            expires_in_days: int,
            created_by_device_id: str | None = None
    ) -> dict:
        """Создать новый инвайт-код."""

        # TODO: Добавить проверку прав доступа (админа)

        # Валидация входных данных
        if not 1 <= expires_in_days <= 365:
            raise ValueError("expires_in_days must be between 1 and 365")

        # Создаём инвайт через репозиторий
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
        """Генерация JWT access и refresh токенов"""
        expires_at = datetime.utcnow() + timedelta(minutes=1)

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