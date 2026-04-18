import uuid
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.mls_group import MlsGroup
from app.repositories.base import BaseRepository

class MlsGroupRepository(BaseRepository[MlsGroup]):
    def __init__(self, session: AsyncSession):
        super().__init__(MlsGroup, session)

    async def get_by_conversation_id(self, conversation_id: uuid.UUID) -> Optional[MlsGroup]:
        return await self.get_by_id(conversation_id)
