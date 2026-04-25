import os
import uuid
from datetime import datetime, timedelta

import jwt
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric import ed25519
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database.redis import RedisClient, delete_challenge, get_challenge, store_challenge
from app.logging_config import get_logger
from app.repositories.device_repo import DeviceRepository
from app.repositories.invite_repo import InviteRepository
from app.repositories.session_repo import SessionRepository

logger = get_logger(__name__)

ACCESS_TOKEN_MINUTES = 60
REFRESH_TOKEN_DAYS = 7


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
        credential_data: bytes,
    ) -> dict:
        invite = await self.invite_repo.get_by_code(invite_code)
        if not invite or invite.is_used or invite.expires_at < datetime.utcnow():
            raise ValueError("Invalid or expired invite code")

        user_id = uuid.uuid4()
        device_uuid = uuid.uuid4()

        device_type = "admin" if invite.is_admin else "primary"

        # Hardware device ids (ANDROID_ID / identifierForVendor) are stable
        # across re-installs and account re-creation. If we find an existing
        # row with this client_device_id, it must belong to a previous account
        # of the same physical device and must already be revoked (orchestrator
        # bulk-revokes all devices on account deletion). Drop the orphan row
        # so the unique index does not block this fresh registration.
        existing = await self.device_repo.get_by_client_device_id(device_id)
        if existing is not None:
            if existing.is_active:
                # Active row with same client_device_id but a different user
                # means previous account-deletion fanout silently failed to
                # revoke it. Refusing here avoids account hijacking.
                raise ValueError(
                    "client_device_id already registered to an active account"
                )
            await self.device_repo.delete(existing)
            await self.session.flush()
            logger.info(
                "auth.register.orphan_device_replaced",
                old_device_id=str(existing.id),
                old_user_id=str(existing.user_id),
                client_device_id=device_id,
            )

        await self.device_repo.create(
            {
                "id": device_uuid,
                "user_id": user_id,
                "client_device_id": device_id,
                "device_name": device_name,
                "device_type": device_type,
                "is_active": True,
                "identity_key_pub": identity_key_pub,
                "init_key_pub": init_key_pub,
                "credential_data": credential_data,
            }
        )

        access_token, refresh_token, expires_at, refresh_expires_at = self._generate_tokens(
            user_id=str(user_id),
            device_id=str(device_uuid),
            device_type=device_type,
        )

        await self.session_repo.create(
            {
                "device_id": device_uuid,
                "access_token": access_token,
                "refresh_token": refresh_token,
                "expires_at": expires_at,
                "refresh_expires_at": refresh_expires_at,
            }
        )

        await self.invite_repo.mark_as_used(invite, used_by_user_id=user_id)

        logger.info(
            "auth.register.success",
            user_id=str(user_id),
            device_id=str(device_uuid),
            device_type=device_type,
            invite_code=invite_code,
        )

        return {
            "user_id": str(user_id),
            "device_id": str(device_uuid),
            "device_type": device_type,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def login_init(self, device_id: str) -> dict:

        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")
        if not device.is_active:
            raise ValueError("Device is inactive or revoked")

        challenge = os.urandom(32)
        await store_challenge(device_id, challenge, settings.CHALLENGE_TTL_SECONDS)

        import base64

        challenge_b64 = base64.b64encode(challenge).decode("utf-8")
        expires_at = int((datetime.utcnow() + timedelta(seconds=settings.CHALLENGE_TTL_SECONDS)).timestamp())
        return {"challenge": challenge_b64, "expires_at": expires_at, "device_id": device_id}

    async def login_complete(
        self,
        device_id: str,
        challenge: str,
        signature: bytes,
        device_name: str = None,
    ) -> dict:
        import base64

        try:
            challenge_bytes = base64.b64decode(challenge)
        except Exception:
            raise ValueError("Invalid challenge format")

        stored_challenge = await get_challenge(device_id)
        if not stored_challenge:
            raise ValueError("Challenge expired or not found. Please restart login.")
        if stored_challenge != challenge_bytes:
            raise ValueError("Challenge mismatch")

        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")
        if not device.is_active:
            raise ValueError("Device is inactive or revoked")

        try:
            public_key = ed25519.Ed25519PublicKey.from_public_bytes(device.identity_key_pub)
            public_key.verify(signature, challenge_bytes)
        except InvalidSignature:
            raise ValueError("Invalid signature. Authentication failed.")
        except Exception as e:
            raise ValueError(f"Signature verification error: {str(e)}")

        await delete_challenge(device_id)

        updates = {"last_seen": datetime.utcnow()}
        if device_name:
            updates["device_name"] = device_name
        await self.device_repo.update(device, updates)

        access_token, refresh_token, expires_at, refresh_expires_at = self._generate_tokens(
            user_id=str(device.user_id),
            device_id=str(device.id),
            device_type=device.device_type,
        )

        await self.session_repo.create(
            {
                "device_id": device.id,
                "access_token": access_token,
                "refresh_token": refresh_token,
                "expires_at": expires_at,
                "refresh_expires_at": refresh_expires_at,
            }
        )

        logger.info(
            "auth.login.success",
            user_id=str(device.user_id),
            device_id=str(device.id),
            device_type=device.device_type,
        )

        return {
            "user_id": str(device.user_id),
            "device_id": str(device.id),
            "device_type": device.device_type,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def refresh_token(self, refresh_token: str) -> dict:
        session = await self.session_repo.get_by_refresh_token(refresh_token)
        if not session:
            raise ValueError("Session not found")
        if session.is_revoked:
            raise ValueError("Session is revoked")
        if session.refresh_expires_at < datetime.utcnow():
            raise ValueError("Refresh token expired")

        await self.session_repo.update(
            session,
            {
                "is_revoked": True,
                "last_used": datetime.utcnow(),
            },
        )

        device = await self.device_repo.get_by_id(session.device_id)
        if not device or not device.is_active:
            raise ValueError("Device not found or inactive")

        access_token, new_refresh_token, expires_at, refresh_expires_at = self._generate_tokens(
            user_id=str(device.user_id),
            device_id=str(device.id),
            device_type=device.device_type,
        )

        await self.session_repo.create(
            {
                "device_id": device.id,
                "access_token": access_token,
                "refresh_token": new_refresh_token,
                "expires_at": expires_at,
                "refresh_expires_at": refresh_expires_at,
            }
        )

        return {
            "user_id": str(device.user_id),
            "device_id": str(device.id),
            "device_type": device.device_type,
            "access_token": access_token,
            "refresh_token": new_refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    async def logout(self, access_token: str) -> dict:
        session = await self.session_repo.get_by_field("access_token", access_token)
        if not session:
            raise ValueError("Session not found")

        try:
            payload = jwt.decode(
                access_token,
                settings.JWT_SECRET,
                algorithms=[settings.JWT_ALGORITHM],
            )
            token_device_id = payload.get("device_id")
            if str(session.device_id) != token_device_id:
                raise ValueError("Token device mismatch")
        except jwt.ExpiredSignatureError:
            pass
        except jwt.InvalidTokenError:
            raise ValueError("Invalid access token")

        await self.session_repo.update(session, {"is_revoked": True, "last_used": datetime.utcnow()})

        redis = await RedisClient.get_instance()
        await redis.delete(f"session:valid:{access_token}")

        logger.info("auth.logout.success", device_id=str(session.device_id))

        return {"success": True, "message": "Successfully logged out"}

    async def create_invite(
        self,
        expires_in_days: int,
        created_by_device_id: str | None = None,
    ) -> dict:
        if not 1 <= expires_in_days <= 365:
            raise ValueError("expires_in_days must be between 1 and 365")

        admin_device_uuid = uuid.UUID(created_by_device_id) if created_by_device_id else None
        invite = await self.invite_repo.create_invite(
            expires_in_days=expires_in_days,
            created_by_admin_device_id=admin_device_uuid,
        )

        logger.info(
            "auth.invite.created",
            invite_code=invite.code,
            expires_in_days=expires_in_days,
            created_by_device_id=created_by_device_id,
        )

        return {
            "code": invite.code,
            "created_at": int(invite.created_at.timestamp()),
            "expires_at": int(invite.expires_at.timestamp()),
            "is_used": invite.is_used,
            "message": f"Invite code created successfully. Valid for {expires_in_days} days.",
        }

    def _generate_tokens(self, user_id: str, device_id: str, device_type: str) -> tuple[str, str, datetime, datetime]:
        expires_at = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_MINUTES)
        refresh_expires_at = datetime.utcnow() + timedelta(days=REFRESH_TOKEN_DAYS)

        base_payload = {
            "sub": user_id,
            "device_id": device_id,
            "device_type": device_type,
        }
        access_token = jwt.encode(
            {**base_payload, "type": "access", "exp": expires_at},
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )
        refresh_token = jwt.encode(
            {**base_payload, "type": "refresh", "exp": refresh_expires_at},
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )
        return access_token, refresh_token, expires_at, refresh_expires_at

    async def validate_token(self, access_token: str) -> dict:
        redis = await RedisClient.get_instance()
        cache_key = f"session:valid:{access_token}"
        cached = await redis.get(cache_key)
        if cached:
            import json

            return json.loads(cached)

        try:
            payload = jwt.decode(
                access_token,
                settings.JWT_SECRET,
                algorithms=[settings.JWT_ALGORITHM],
            )
        except jwt.ExpiredSignatureError:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}
        except jwt.InvalidTokenError:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}

        expires_at = payload.get("exp", 0)

        session_record = await self.session_repo.get_by_access_token(access_token)
        if not session_record or session_record.is_revoked:
            return {"valid": False, "user_id": "", "device_id": "", "device_type": "", "expires_at": 0}

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

        import json

        ttl = max(0, expires_at - int(datetime.utcnow().timestamp()))
        if ttl > 0:
            await redis.setex(cache_key, ttl, json.dumps(result))

        return result
