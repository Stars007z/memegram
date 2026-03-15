import uuid
from sqlalchemy import select
from app.models.device import Device
from app.repositories.base import BaseRepository
from sqlalchemy.ext.asyncio import AsyncSession

class DeviceRepository(BaseRepository[Device]):
    def __init__(self, session: AsyncSession):
        super().__init__(Device, session)

    async def get_by_user_id(self, user_id: uuid.UUID) -> list[Device]:
        query = select(Device).where(Device.user_id == user_id)
        result = await self.session.execute(query)
        return result.scalars().all()

    async def get_by_device_id(self, device_id: str) -> Device:
        return await self.get_by_id(uuid.UUID(device_id))