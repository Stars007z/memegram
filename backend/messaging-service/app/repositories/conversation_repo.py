import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy import select, and_

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.conversation import Conversation
from app.models.conversation_member import ConversationMember
from app.repositories.base import BaseRepository

class ConversationRepository(BaseRepository[Conversation]):
    def __init__(self, session: AsyncSession):
        super().__init__(Conversation, session)

    async def find_direct_between(
        self, user_a: uuid.UUID, user_b: uuid.UUID,
    ) -> Optional[Conversation]:
        """Check if a direct conversation already exists between two users."""
        lo, hi = sorted([user_a, user_b])
        subq_a = (
            select(ConversationMember.conversation_id)
            .where(
                ConversationMember.user_id == lo,
                ConversationMember.left_at.is_(None),
            )
        )
        subq_b = (
            select(ConversationMember.conversation_id)
            .where(
                ConversationMember.user_id == hi,
                ConversationMember.left_at.is_(None),
            )
        )
        query = (
            select(Conversation)
            .where(
                Conversation.type == "direct",
                Conversation.id.in_(subq_a),
                Conversation.id.in_(subq_b),
            )
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none()

    async def get_user_conversations(
        self,
        user_id: uuid.UUID,
        limit: int,
        cursor_time: Optional[datetime] = None,
        cursor_id: Optional[uuid.UUID] = None,
    ) -> list[Conversation]:
        """Cursor-based pagination by (last_activity_at DESC, id DESC)."""
        member_subq = (
            select(ConversationMember.conversation_id)
            .where(
                ConversationMember.user_id == user_id,
                ConversationMember.left_at.is_(None),
            )
        )
        query = (
            select(Conversation)
            .where(Conversation.id.in_(member_subq))
            .order_by(Conversation.last_activity_at.desc(), Conversation.id.desc())
            .limit(limit)
        )

        if cursor_time and cursor_id:
            query = query.where(
                and_(
                    Conversation.last_activity_at <= cursor_time,
                    ~and_(
                        Conversation.last_activity_at == cursor_time,
                        Conversation.id >= cursor_id,
                    ),
                )
            )

        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def update_avatar(
        self, conversation_id: uuid.UUID, avatar_media_id: uuid.UUID | None,
    ) -> None:
        conv = await self.get_by_id(conversation_id)
        if conv:
            conv.avatar_media_id = avatar_media_id
            await self.session.flush()

    async def update_name(
        self, conversation_id: uuid.UUID, name: str,
    ) -> None:
        conv = await self.get_by_id(conversation_id)
        if conv:
            conv.name = name
            await self.session.flush()

    async def update_last_message(
        self, conversation_id: uuid.UUID, message_id: uuid.UUID,
    ) -> None:
        conv = await self.get_by_id(conversation_id)
        if conv:
            conv.last_message_id = message_id
            conv.last_activity_at = datetime.utcnow()
            await self.session.flush()
