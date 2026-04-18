import uuid
from typing import Optional
from sqlalchemy import select, func, delete
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.blocked_user import BlockedUser
from app.repositories.base import BaseRepository

class BlockedUserRepository(BaseRepository[BlockedUser]):
    def __init__(self, session: AsyncSession):
        super().__init__(BlockedUser, session)

    async def get_by_pair(self, user_id: uuid.UUID, blocked_user_id: uuid.UUID) -> Optional[BlockedUser]:
        result = await self.session.execute(
            select(BlockedUser).where(
                BlockedUser.user_id == user_id,
                BlockedUser.blocked_user_id == blocked_user_id,
            )
        )
        return result.scalar_one_or_none()

    async def exists(self, user_id: uuid.UUID, blocked_user_id: uuid.UUID) -> bool:
        result = await self.session.execute(
            select(func.count()).select_from(BlockedUser).where(
                BlockedUser.user_id == user_id,
                BlockedUser.blocked_user_id == blocked_user_id,
            )
        )
        return result.scalar() > 0

    async def get_paginated(
        self, user_id: uuid.UUID, limit: int, offset: int
    ) -> list[BlockedUser]:
        result = await self.session.execute(
            select(BlockedUser)
            .where(BlockedUser.user_id == user_id)
            .order_by(BlockedUser.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())

    async def count_by_user(self, user_id: uuid.UUID) -> int:
        result = await self.session.execute(
            select(func.count()).select_from(BlockedUser).where(BlockedUser.user_id == user_id)
        )
        return result.scalar()

    async def delete_by_pair(self, user_id: uuid.UUID, blocked_user_id: uuid.UUID) -> None:
        await self.session.execute(
            delete(BlockedUser).where(
                BlockedUser.user_id == user_id,
                BlockedUser.blocked_user_id == blocked_user_id,
            )
        )
