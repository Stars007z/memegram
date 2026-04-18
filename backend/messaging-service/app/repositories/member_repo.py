import uuid
from typing import Optional

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.conversation_member import ConversationMember
from app.repositories.base import BaseRepository


class MemberRepository(BaseRepository[ConversationMember]):
    def __init__(self, session: AsyncSession):
        super().__init__(ConversationMember, session)

    async def get_active_member(
        self,
        conversation_id: uuid.UUID,
        user_id: uuid.UUID,
    ) -> Optional[ConversationMember]:
        query = select(ConversationMember).where(
            ConversationMember.conversation_id == conversation_id,
            ConversationMember.user_id == user_id,
            ConversationMember.left_at.is_(None),
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none()

    async def get_member(
        self,
        conversation_id: uuid.UUID,
        user_id: uuid.UUID,
    ) -> Optional[ConversationMember]:
        """Get member regardless of left_at status (includes left members)."""
        query = select(ConversationMember).where(
            ConversationMember.conversation_id == conversation_id,
            ConversationMember.user_id == user_id,
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none()

    async def get_active_members(
        self,
        conversation_id: uuid.UUID,
    ) -> list[ConversationMember]:
        query = select(ConversationMember).where(
            ConversationMember.conversation_id == conversation_id,
            ConversationMember.left_at.is_(None),
        )
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def get_user_conversation_ids(
        self,
        user_id: uuid.UUID,
    ) -> list[uuid.UUID]:
        query = select(ConversationMember.conversation_id).where(
            ConversationMember.user_id == user_id,
            ConversationMember.left_at.is_(None),
        )
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def is_member(
        self,
        conversation_id: uuid.UUID,
        user_id: uuid.UUID,
    ) -> bool:
        member = await self.get_active_member(conversation_id, user_id)
        return member is not None

    async def has_role(
        self,
        conversation_id: uuid.UUID,
        user_id: uuid.UUID,
        roles: list[str],
    ) -> bool:
        query = select(ConversationMember).where(
            ConversationMember.conversation_id == conversation_id,
            ConversationMember.user_id == user_id,
            ConversationMember.left_at.is_(None),
            ConversationMember.role.in_(roles),
        )
        result = await self.session.execute(query)
        return result.scalar_one_or_none() is not None

    async def update_role(
        self,
        conversation_id: uuid.UUID,
        user_id: uuid.UUID,
        new_role: str,
    ) -> Optional[ConversationMember]:
        member = await self.get_active_member(conversation_id, user_id)
        if not member:
            return None
        member.role = new_role
        await self.session.flush()
        return member
