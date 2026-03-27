import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.mls_welcome_message import MlsWelcomeMessage
from app.repositories.base import BaseRepository


class MlsWelcomeRepository(BaseRepository[MlsWelcomeMessage]):
    def __init__(self, session: AsyncSession):
        super().__init__(MlsWelcomeMessage, session)

    async def get_pending_for_device(
        self, device_id: uuid.UUID,
    ) -> list[MlsWelcomeMessage]:
        query = (
            select(MlsWelcomeMessage)
            .where(
                MlsWelcomeMessage.recipient_device_id == device_id,
                MlsWelcomeMessage.delivered_at.is_(None),
            )
            .order_by(MlsWelcomeMessage.created_at.asc())
        )
        result = await self.session.execute(query)
        return list(result.scalars().all())
