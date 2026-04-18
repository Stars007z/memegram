import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.mls_commit_message import MlsCommitMessage
from app.repositories.base import BaseRepository

class MlsCommitRepository(BaseRepository[MlsCommitMessage]):
    def __init__(self, session: AsyncSession):
        super().__init__(MlsCommitMessage, session)

    async def get_since_epoch(
        self, conversation_id: uuid.UUID, since_epoch: int,
    ) -> list[MlsCommitMessage]:
        query = (
            select(MlsCommitMessage)
            .where(
                MlsCommitMessage.conversation_id == conversation_id,
                MlsCommitMessage.epoch > since_epoch,
            )
            .order_by(MlsCommitMessage.epoch.asc())
        )
        result = await self.session.execute(query)
        return list(result.scalars().all())
