import uuid
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device_registration import DeviceRegistration
from app.repositories.base import BaseRepository


class DeviceRegistrationRepository(BaseRepository[DeviceRegistration]):
    def __init__(self, session: AsyncSession):
        super().__init__(DeviceRegistration, session)

    async def get_by_registration_id(self, registration_id: uuid.UUID) -> DeviceRegistration | None:
        return await self.get_by_id(registration_id)

    async def get_by_code(self, code: str) -> DeviceRegistration | None:
        return await self.get_by_field("registration_code", code)

    async def get_pending_by_user(self, user_id: uuid.UUID) -> list[DeviceRegistration]:
        query = (
            select(DeviceRegistration)
            .where(
                DeviceRegistration.user_id == user_id,
                DeviceRegistration.status.in_(["pending", "awaiting_confirmation"]),
                DeviceRegistration.expires_at > datetime.utcnow(),
            )
            .order_by(DeviceRegistration.created_at.desc())
        )
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def get_active_registration(self, registration_id: uuid.UUID) -> DeviceRegistration | None:
        query = select(DeviceRegistration).where(
            DeviceRegistration.id == registration_id,
            DeviceRegistration.expires_at > datetime.utcnow(),
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none()
