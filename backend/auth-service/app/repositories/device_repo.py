import uuid
from datetime import datetime

from sqlalchemy import case, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device import Device
from app.repositories.base import BaseRepository


class DeviceRepository(BaseRepository[Device]):
    def __init__(self, session: AsyncSession):
        super().__init__(Device, session)

    async def get_by_user_id(self, user_id: uuid.UUID) -> list[Device]:
        query = select(Device).where(Device.user_id == user_id, Device.deleted_at.is_(None))
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def get_by_device_id(self, device_id: str) -> Device | None:
        """Lookup by primary key devices.id (UUID issued to client at registration)."""
        try:
            return await self.get_by_id(uuid.UUID(device_id))
        except (ValueError, AttributeError):
            return None

    async def get_by_client_device_id(self, client_device_id: str | None) -> Device | None:
        """Lookup by client-provided device identifier (client_device_id)."""
        if not client_device_id:
            return None
        return await self.get_by_field("client_device_id", client_device_id)

    async def get_active_by_user_id(self, user_id: uuid.UUID) -> list[Device]:
        query = select(Device).where(
            Device.user_id == user_id,
            Device.is_active == True,
        )
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def get_primary_device(self, user_id: uuid.UUID) -> Device | None:
        query = select(Device).where(
            Device.user_id == user_id,
            Device.device_type == "primary",
            Device.is_active == True,
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none()

    async def get_stats(self, user_id: uuid.UUID) -> dict:
        query = select(
            func.count().label("total"),
            func.count().filter(Device.is_active == True).label("active"),
            func.count().filter(Device.device_type == "primary").label("primary"),
            func.max(Device.last_seen).label("last_activity"),
        ).where(Device.user_id == user_id)
        result = await self.session.execute(query)
        row = result.one()

        type_query = (
            select(Device.device_type, func.count().label("cnt"))
            .where(Device.user_id == user_id, Device.is_active == True)
            .group_by(Device.device_type)
        )
        type_result = await self.session.execute(type_query)
        type_stats = {r.device_type: r.cnt for r in type_result.all()}

        return {
            "total_count": row.total,
            "active_count": row.active,
            "primary_count": row.primary,
            "type_stats": type_stats,
            "last_activity_at": row.last_activity,
        }

    async def revoke_devices(
        self,
        devices: list[Device],
        revoked_by_device_id: uuid.UUID,
    ) -> list[Device]:
        now = datetime.utcnow()
        for device in devices:
            device.is_active = False
            device.revoked_at = now
            device.revoked_by_device_id = revoked_by_device_id
        return devices
