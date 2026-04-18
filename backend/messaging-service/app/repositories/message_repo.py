import uuid
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.message import Message
from app.repositories.base import BaseRepository

class MessageRepository(BaseRepository[Message]):
    def __init__(self, session: AsyncSession):
        super().__init__(Message, session)

    async def get_by_client_message_id(
        self, client_message_id: uuid.UUID,
    ) -> Optional[Message]:
        return await self.get_by_field("client_message_id", client_message_id)

    async def get_messages_before(
        self,
        conversation_id: uuid.UUID,
        before_message_id: Optional[uuid.UUID],
        limit: int,
    ) -> list[Message]:
        query = (
            select(Message)
            .where(
                Message.conversation_id == conversation_id,
                Message.deleted_at.is_(None),
            )
            .order_by(Message.created_at.desc())
            .limit(limit + 1)
        )

        if before_message_id:
            anchor_q = select(Message.created_at).where(Message.id == before_message_id)
            result = await self.session.execute(anchor_q)
            anchor_time = result.scalar_one_or_none()
            if anchor_time:
                query = query.where(Message.created_at < anchor_time)

        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def get_last_message(
        self, conversation_id: uuid.UUID,
    ) -> Optional[Message]:
        query = (
            select(Message)
            .where(
                Message.conversation_id == conversation_id,
                Message.deleted_at.is_(None),
            )
            .order_by(Message.created_at.desc())
            .limit(1)
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none()
