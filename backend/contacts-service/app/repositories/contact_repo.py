import uuid
from typing import Optional
from sqlalchemy import select, func, delete
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.contact import Contact
from app.repositories.base import BaseRepository

class ContactRepository(BaseRepository[Contact]):
    def __init__(self, session: AsyncSession):
        super().__init__(Contact, session)

    async def get_by_pair(self, user_id: uuid.UUID, contact_user_id: uuid.UUID) -> Optional[Contact]:
        result = await self.session.execute(
            select(Contact).where(
                Contact.user_id == user_id,
                Contact.contact_user_id == contact_user_id,
            )
        )
        return result.scalar_one_or_none()

    async def exists(self, user_id: uuid.UUID, contact_user_id: uuid.UUID) -> bool:
        result = await self.session.execute(
            select(func.count()).select_from(Contact).where(
                Contact.user_id == user_id,
                Contact.contact_user_id == contact_user_id,
            )
        )
        return result.scalar() > 0

    async def get_paginated(
        self, user_id: uuid.UUID, limit: int, offset: int
    ) -> list[Contact]:
        result = await self.session.execute(
            select(Contact)
            .where(Contact.user_id == user_id)
            .order_by(Contact.is_favorite.desc(), Contact.created_at.asc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())

    async def count_by_user(self, user_id: uuid.UUID) -> int:
        result = await self.session.execute(
            select(func.count()).select_from(Contact).where(Contact.user_id == user_id)
        )
        return result.scalar()

    async def delete_by_pair(self, user_id: uuid.UUID, contact_user_id: uuid.UUID) -> None:
        await self.session.execute(
            delete(Contact).where(
                Contact.user_id == user_id,
                Contact.contact_user_id == contact_user_id,
            )
        )

    async def delete_mutual(self, user_a: uuid.UUID, user_b: uuid.UUID) -> None:
        """Удалить оба направления (user_a→user_b и user_b→user_a)."""
        await self.session.execute(
            delete(Contact).where(
                ((Contact.user_id == user_a) & (Contact.contact_user_id == user_b))
                | ((Contact.user_id == user_b) & (Contact.contact_user_id == user_a))
            )
        )
