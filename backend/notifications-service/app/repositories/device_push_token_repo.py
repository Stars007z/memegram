import uuid
from datetime import datetime
from typing import Sequence

from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device_push_token import DevicePushToken

class DevicePushTokenRepository:

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def upsert(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        platform: str,
        push_token: str,
    ) -> DevicePushToken:
        """Insert or update a push token for the given device (UPSERT on device_id)."""
        now = datetime.utcnow()
        stmt = (
            pg_insert(DevicePushToken)
            .values(
                id=uuid.uuid4(),
                user_id=user_id,
                device_id=device_id,
                platform=platform,
                push_token=push_token,
                is_active=True,
                consecutive_failures=0,
                created_at=now,
                updated_at=now,
                last_success_at=None,
            )
            .on_conflict_do_update(
                index_elements=["device_id"],
                set_={
                    "push_token": push_token,
                    "platform": platform,
                    "is_active": True,
                    "consecutive_failures": 0,
                    "updated_at": now,
                },
            )
            .returning(DevicePushToken)
        )
        result = await self._session.execute(stmt)
        return result.scalar_one()

    async def deactivate(self, user_id: uuid.UUID, device_id: uuid.UUID) -> bool:
        """Set is_active=false for the device. Returns True if a row was updated."""
        stmt = (
            update(DevicePushToken)
            .where(
                DevicePushToken.device_id == device_id,
                DevicePushToken.user_id == user_id,
            )
            .values(is_active=False, updated_at=datetime.utcnow())
        )
        result = await self._session.execute(stmt)
        return result.rowcount > 0

    async def get_active_tokens_for_users(
        self, user_ids: list[uuid.UUID],
    ) -> Sequence[DevicePushToken]:
        """Return all active push tokens for the given user IDs."""
        stmt = (
            select(DevicePushToken)
            .where(
                DevicePushToken.user_id.in_(user_ids),
                DevicePushToken.is_active == True,
            )
        )
        result = await self._session.execute(stmt)
        return result.scalars().all()

    async def mark_success(self, token_id: uuid.UUID) -> None:
        """Mark a token as successfully delivered."""
        stmt = (
            update(DevicePushToken)
            .where(DevicePushToken.id == token_id)
            .values(
                consecutive_failures=0,
                last_success_at=datetime.utcnow(),
                updated_at=datetime.utcnow(),
            )
        )
        await self._session.execute(stmt)

    async def increment_failure(self, token_id: uuid.UUID, max_failures: int = 3) -> None:
        """Increment consecutive failures. Deactivate if threshold exceeded."""
        stmt = (
            update(DevicePushToken)
            .where(DevicePushToken.id == token_id)
            .values(
                consecutive_failures=DevicePushToken.consecutive_failures + 1,
                updated_at=datetime.utcnow(),
            )
        )
        await self._session.execute(stmt)

        deactivate_stmt = (
            update(DevicePushToken)
            .where(
                DevicePushToken.id == token_id,
                DevicePushToken.consecutive_failures >= max_failures,
            )
            .values(is_active=False, updated_at=datetime.utcnow())
        )
        await self._session.execute(deactivate_stmt)

    async def deactivate_token(self, token_id: uuid.UUID) -> None:
        """Immediately deactivate a token (permanent error like UNREGISTERED)."""
        stmt = (
            update(DevicePushToken)
            .where(DevicePushToken.id == token_id)
            .values(is_active=False, updated_at=datetime.utcnow())
        )
        await self._session.execute(stmt)
