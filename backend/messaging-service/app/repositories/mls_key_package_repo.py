import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy import select, func, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.mls_key_package import MlsKeyPackage
from app.repositories.base import BaseRepository


class MlsKeyPackageRepository(BaseRepository[MlsKeyPackage]):
    def __init__(self, session: AsyncSession):
        super().__init__(MlsKeyPackage, session)

    async def consume_one(
        self, user_id: uuid.UUID, device_id: uuid.UUID,
    ) -> Optional[MlsKeyPackage]:
        """Atomically fetch and consume one available key package (FOR UPDATE SKIP LOCKED)."""
        query = (
            select(MlsKeyPackage)
            .where(
                MlsKeyPackage.user_id == user_id,
                MlsKeyPackage.device_id == device_id,
                MlsKeyPackage.consumed_at.is_(None),
            )
            .order_by(MlsKeyPackage.created_at.asc())
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        result = await self.session.execute(query)
        package = result.scalar_one_or_none()
        if package:
            package.consumed_at = datetime.utcnow()
            await self.session.commit()
        return package

    async def count_available(
        self, user_id: uuid.UUID, device_id: uuid.UUID,
    ) -> int:
        query = (
            select(func.count())
            .select_from(MlsKeyPackage)
            .where(
                MlsKeyPackage.user_id == user_id,
                MlsKeyPackage.device_id == device_id,
                MlsKeyPackage.consumed_at.is_(None),
            )
        )
        result = await self.session.execute(query)
        return result.scalar()

    async def delete_by_device(
        self, user_id: uuid.UUID, device_id: uuid.UUID,
        only_unconsumed: bool = True,
    ) -> int:
        """
        Hard-delete all key packages for (user_id, device_id).
        Used when client wipes its local MLS store (logout/reset) and
        re-uploads a fresh batch — old KPs must not be served to peers
        because their private halves no longer exist on the client.

        Returns number of deleted rows.
        """
        stmt = delete(MlsKeyPackage).where(
            MlsKeyPackage.user_id == user_id,
            MlsKeyPackage.device_id == device_id,
        )
        if only_unconsumed:
            stmt = stmt.where(MlsKeyPackage.consumed_at.is_(None))
        result = await self.session.execute(stmt)
        await self.session.commit()
        return result.rowcount or 0