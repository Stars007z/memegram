import uuid
import secrets
import os
from datetime import datetime, timedelta
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.exceptions import InvalidSignature
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, update

from app.repositories.device_repo import DeviceRepository
from app.repositories.device_registration_repo import DeviceRegistrationRepository
from app.repositories.session_repo import SessionRepository
from app.services.auth_service import AuthService
from app.config import settings

REGISTRATION_TTL_MINUTES = 10


class DeviceService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.device_repo = DeviceRepository(session)
        self.registration_repo = DeviceRegistrationRepository(session)
        self.session_repo = SessionRepository(session)
        self._auth_service = AuthService(session)

    # ── Init device addition ──────────────────────────────────────────

    async def init_device_addition(self, user_id: str, device_id: str) -> dict:
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")
        if str(device.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if device.device_type != "primary":
            raise PermissionError("Only primary device can initiate device addition")
        if not device.is_active:
            raise ValueError("Device is inactive")

        registration_id = uuid.uuid4()
        code = f"{secrets.randbelow(10**6):06d}"
        expires_at = datetime.utcnow() + timedelta(minutes=REGISTRATION_TTL_MINUTES)

        await self.registration_repo.create({
            "id": registration_id,
            "registration_code": code,
            "user_id": uuid.UUID(user_id),
            "initiated_by_device_id": device.id,
            "expires_at": expires_at,
            "status": "pending",
        })

        return {
            "registration_id": str(registration_id),
            "expires_at": int(expires_at.timestamp()),
            "registration_code": code,
        }

    # ── Submit new device data ────────────────────────────────────────

    async def submit_device_data(
        self,
        registration_id: str,
        registration_code: str,
        device_id: str,
        device_name: str,
        device_type: str,
        identity_key_pub: bytes,
        init_key_pub: bytes,
        credential_data: bytes,
    ) -> dict:
        reg = await self.registration_repo.get_active_registration(uuid.UUID(registration_id))
        if not reg:
            raise ValueError("Registration not found or expired")
        if reg.registration_code != registration_code:
            raise ValueError("Invalid registration code")
        if reg.status not in ("pending",):
            raise ValueError(f"Registration is in unexpected state: {reg.status}")

        await self.registration_repo.update(reg, {
            "device_id": device_id,
            "device_name": device_name,
            "device_type": device_type or "secondary",
            "identity_key_pub": identity_key_pub,
            "init_key_pub": init_key_pub,
            "credential_data": credential_data,
            "status": "awaiting_confirmation",
        })

        return {
            "status": "awaiting_confirmation",
            "expires_at": int(reg.expires_at.timestamp()),
        }

    # ── Get device addition status ────────────────────────────────────

    async def get_device_addition_status(self, registration_id: str) -> dict:
        reg = await self.registration_repo.get_by_registration_id(uuid.UUID(registration_id))
        if not reg:
            raise ValueError("Registration not found")

        result = {
            "status": reg.status,
            "expires_at": int(reg.expires_at.timestamp()),
            "device": None,
            "access_token": "",
            "refresh_token": "",
            "token_expires_at": 0,
        }

        if reg.status == "confirmed" and reg.confirmed_device_id:
            device = await self.device_repo.get_by_id(reg.confirmed_device_id)
            if device:
                result["device"] = self._device_to_dict(device)
            if reg.result_access_token:
                result["access_token"] = reg.result_access_token
                result["refresh_token"] = reg.result_refresh_token or ""
                result["token_expires_at"] = (
                    int(reg.result_token_expires_at.timestamp())
                    if reg.result_token_expires_at
                    else 0
                )

        return result

    # ── Get pending device additions ──────────────────────────────────

    async def get_pending_device_additions(self, user_id: str, device_id: str) -> list[dict]:
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")
        if str(device.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if device.device_type != "primary":
            raise PermissionError("Only primary device can view pending additions")

        registrations = await self.registration_repo.get_pending_by_user(uuid.UUID(user_id))
        return [
            {
                "registration_id": str(r.id),
                "registration_code": r.registration_code,
                "expires_at": int(r.expires_at.timestamp()),
                "status": r.status,
                "device_id": r.device_id or "",
                "device_name": r.device_name or "",
                "device_type": r.device_type or "",
                "created_at": int(r.created_at.timestamp()),
            }
            for r in registrations
        ]

    # ── Confirm device addition ───────────────────────────────────────

    async def confirm_device_addition(
        self,
        user_id: str,
        device_id: str,
        registration_id: str,
        confirm: bool,
        new_device_name: str = "",
    ) -> dict:
        requesting_device = await self.device_repo.get_by_device_id(device_id)
        if not requesting_device:
            raise ValueError("Requesting device not found")
        if str(requesting_device.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if requesting_device.device_type != "primary":
            raise PermissionError("Only primary device can confirm additions")

        reg = await self.registration_repo.get_active_registration(uuid.UUID(registration_id))
        if not reg:
            raise ValueError("Registration not found or expired")
        if str(reg.user_id) != user_id:
            raise PermissionError("Registration does not belong to user")
        if reg.status != "awaiting_confirmation":
            raise ValueError(f"Registration is not awaiting confirmation (status: {reg.status})")

        if not confirm:
            await self.registration_repo.update(reg, {
                "status": "rejected",
                "rejected_at": datetime.utcnow(),
                "rejection_reason": "Rejected by primary device",
            })
            return {
                "new_device_id": "",
                "user_id": user_id,
                "status": "rejected",
                "message": "Device addition rejected",
                "access_token": "",
                "refresh_token": "",
                "expires_at": 0,
            }

        device_uuid = uuid.uuid4()
        final_name = new_device_name or reg.device_name or "Unknown device"

        await self.device_repo.create({
            "id": device_uuid,
            "user_id": uuid.UUID(user_id),
            "client_device_id": reg.device_id,
            "device_name": final_name,
            "device_type": "secondary",
            "is_active": True,
            "identity_key_pub": reg.identity_key_pub,
            "init_key_pub": reg.init_key_pub,
            "credential_data": reg.credential_data,
        })

        access_token, refresh_token, expires_at, refresh_expires_at = (
            self._auth_service._generate_tokens(
                user_id=user_id,
                device_id=str(device_uuid),
                is_primary=False,
            )
        )

        await self.session_repo.create({
            "device_id": device_uuid,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": expires_at,
            "refresh_expires_at": refresh_expires_at,
        })

        await self.registration_repo.update(reg, {
            "status": "confirmed",
            "confirmed_at": datetime.utcnow(),
            "confirmed_by_device_id": requesting_device.id,
            "confirmed_device_id": device_uuid,
            "result_access_token": access_token,
            "result_refresh_token": refresh_token,
            "result_token_expires_at": expires_at,
        })

        return {
            "new_device_id": str(device_uuid),
            "user_id": user_id,
            "status": "confirmed",
            "message": "Device added successfully",
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at.timestamp()),
        }

    # ── Get devices ───────────────────────────────────────────────────

    async def get_devices(self, user_id: str) -> list[dict]:
        devices = await self.device_repo.get_by_user_id(uuid.UUID(user_id))
        return [self._device_to_dict(d) for d in devices]

    # ── Get single device ─────────────────────────────────────────────

    async def get_device(self, user_id: str, device_id: str) -> dict:
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")
        if str(device.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        return self._device_to_dict(device)

    # ── Revoke device ─────────────────────────────────────────────────

    async def revoke_device(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_id: str,
        reason: str,
    ) -> dict:
        requesting = await self.device_repo.get_by_device_id(requesting_device_id)
        if not requesting:
            raise ValueError("Requesting device not found")
        if str(requesting.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if requesting.device_type != "primary":
            raise PermissionError("Only primary device can revoke other devices")

        target = await self.device_repo.get_by_device_id(target_device_id)
        if not target:
            raise ValueError("Target device not found")
        if str(target.user_id) != user_id:
            raise PermissionError("Target device does not belong to user")
        if target.device_type == "primary":
            raise ValueError("Cannot revoke primary device")
        if not target.is_active:
            raise ValueError("Device is already revoked")

        now = datetime.utcnow()
        await self.device_repo.update(target, {
            "is_active": False,
            "revoked_at": now,
            "revoked_by_device_id": requesting.id,
        })

        await self._revoke_device_sessions(target.id)

        return {
            "success": True,
            "message": f"Device revoked: {reason}",
            "revoked_device_id": str(target.id),
            "revoked_at": int(now.timestamp()),
        }

    # ── Update device keys ────────────────────────────────────────────

    async def update_device_keys(
        self,
        user_id: str,
        device_id: str,
        identity_key_pub: bytes,
        init_key_pub: bytes,
        credential_data: bytes,
    ) -> dict:
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")
        if str(device.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if not device.is_active:
            raise ValueError("Device is inactive")

        now = datetime.utcnow()
        await self.device_repo.update(device, {
            "identity_key_pub": identity_key_pub,
            "init_key_pub": init_key_pub,
            "credential_data": credential_data,
            "last_seen": now,
        })

        return {
            "success": True,
            "message": "Device keys updated",
            "updated_at": int(now.timestamp()),
        }

    # ── Rename device ─────────────────────────────────────────────────

    async def rename_device(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_id: str,
        new_name: str,
    ) -> dict:
        requesting = await self.device_repo.get_by_device_id(requesting_device_id)
        if not requesting:
            raise ValueError("Requesting device not found")
        if str(requesting.user_id) != user_id:
            raise PermissionError("Device does not belong to user")

        target = await self.device_repo.get_by_device_id(target_device_id)
        if not target:
            raise ValueError("Target device not found")
        if str(target.user_id) != user_id:
            raise PermissionError("Target device does not belong to user")

        can_rename = (
            requesting_device_id == target_device_id
            or requesting.device_type == "primary"
        )
        if not can_rename:
            raise PermissionError("Only the device itself or the primary device can rename")

        await self.device_repo.update(target, {"device_name": new_name})

        return {
            "success": True,
            "new_name": new_name,
            "message": "Device renamed successfully",
        }

    # ── Verify device ─────────────────────────────────────────────────

    async def verify_device(self, device_id: str, signature: bytes) -> dict:
        device = await self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError("Device not found")

        try:
            public_key = ed25519.Ed25519PublicKey.from_public_bytes(device.identity_key_pub)
            challenge = device_id.encode("utf-8")
            public_key.verify(signature, challenge)
        except InvalidSignature:
            return {"valid": False, "message": "Invalid signature"}
        except Exception as e:
            return {"valid": False, "message": f"Verification error: {e}"}

        return {"valid": True, "message": "Device verified successfully"}

    # ── Transfer primary ──────────────────────────────────────────────

    async def transfer_primary(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_id: str,
    ) -> dict:
        requesting = await self.device_repo.get_by_device_id(requesting_device_id)
        if not requesting:
            raise ValueError("Requesting device not found")
        if str(requesting.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if requesting.device_type != "primary":
            raise PermissionError("Only primary device can transfer primary status")

        target = await self.device_repo.get_by_device_id(target_device_id)
        if not target:
            raise ValueError("Target device not found")
        if str(target.user_id) != user_id:
            raise PermissionError("Target device does not belong to user")
        if not target.is_active:
            raise ValueError("Target device is inactive")
        if target.id == requesting.id:
            raise ValueError("Cannot transfer primary to the same device")

        await self.device_repo.update(requesting, {"device_type": "secondary"})
        await self.device_repo.update(target, {"device_type": "primary"})

        return {
            "success": True,
            "new_primary_device_id": str(target.id),
            "message": "Primary status transferred successfully",
        }

    # ── Bulk revoke ───────────────────────────────────────────────────

    async def bulk_revoke_devices(
        self,
        user_id: str,
        requesting_device_id: str,
        target_device_ids: list[str],
        reason: str,
    ) -> dict:
        requesting = await self.device_repo.get_by_device_id(requesting_device_id)
        if not requesting:
            raise ValueError("Requesting device not found")
        if str(requesting.user_id) != user_id:
            raise PermissionError("Device does not belong to user")
        if requesting.device_type != "primary":
            raise PermissionError("Only primary device can bulk revoke")

        revoked_ids = []
        now = datetime.utcnow()

        for tid in target_device_ids:
            target = await self.device_repo.get_by_device_id(tid)
            if not target:
                continue
            if str(target.user_id) != user_id:
                continue
            if target.device_type == "primary":
                continue
            if not target.is_active:
                continue

            await self.device_repo.update(target, {
                "is_active": False,
                "revoked_at": now,
                "revoked_by_device_id": requesting.id,
            })
            await self._revoke_device_sessions(target.id)
            revoked_ids.append(str(target.id))

        return {
            "success": True,
            "revoked_count": len(revoked_ids),
            "revoked_device_ids": revoked_ids,
        }

    # ── Device stats ──────────────────────────────────────────────────

    async def get_device_stats(self, user_id: str) -> dict:
        stats = await self.device_repo.get_stats(uuid.UUID(user_id))
        last_activity = stats["last_activity_at"]
        return {
            "total_count": stats["total_count"],
            "active_count": stats["active_count"],
            "primary_count": stats["primary_count"],
            "type_stats": stats["type_stats"],
            "last_activity_at": int(last_activity.timestamp()) if last_activity else 0,
        }

    # ── Helpers ────────────────────────────────────────────────────────

    async def _revoke_device_sessions(self, device_id: uuid.UUID) -> None:
        from app.models.session import Session as SessionModel
        from sqlalchemy import update as sa_update

        stmt = (
            sa_update(SessionModel)
            .where(SessionModel.device_id == device_id, SessionModel.is_revoked == False)
            .values(is_revoked=True, last_used=datetime.utcnow())
        )
        await self.session.execute(stmt)

    @staticmethod
    def _device_to_dict(device) -> dict:
        return {
            "id": str(device.id),
            "user_id": str(device.user_id),
            "client_device_id": device.client_device_id,
            "device_name": device.device_name or "",
            "device_type": device.device_type,
            "is_active": device.is_active,
            "created_at": int(device.created_at.timestamp()) if device.created_at else 0,
            "last_seen": int(device.last_seen.timestamp()) if device.last_seen else 0,
            "identity_key_pub": device.identity_key_pub,
            "init_key_pub": device.init_key_pub,
            "revoked_at": int(device.revoked_at.timestamp()) if device.revoked_at else 0,
        }
