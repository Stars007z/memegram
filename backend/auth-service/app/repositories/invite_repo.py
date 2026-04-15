# app/repositories/invite_repo.py
import uuid
import secrets
from datetime import datetime, timedelta
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.invite import Invite
from app.repositories.base import BaseRepository


class InviteRepository(BaseRepository[Invite]):
    def __init__(self, session: AsyncSession):
        super().__init__(Invite, session)

    async def get_by_code(self, code: str) -> Invite | None:
        """Получить инвайт по коду"""
        return await self.get_by_field("code", code)

    async def mark_as_used(self, invite: Invite, used_by_user_id: uuid.UUID) -> Invite:
        """Пометить инвайт как использованный"""
        invite.is_used = True
        invite.used_by_user_id = used_by_user_id
        invite.used_at = datetime.utcnow()
        return invite

    async def create_invite(
            self,
            expires_in_days: int,
            created_by_admin_device_id: uuid.UUID | None = None
    ) -> Invite:
        """Создать новый инвайт-код."""

        # Валидация срока действия
        if not 1 <= expires_in_days <= 365:
            raise ValueError("expires_in_days must be between 1 and 365")

        code = "-".join([
            secrets.token_urlsafe(6) for _ in range(4)
        ])

        now = datetime.utcnow()
        expires_at = now + timedelta(days=expires_in_days)

        invite = await self.create({
            "id": uuid.uuid4(),
            "code": code,
            "created_at": now,
            "expires_at": expires_at,
            "is_used": False,
            "is_admin": False,
            "created_by_admin_device_id": created_by_admin_device_id,
        })

        return invite