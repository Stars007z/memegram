import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy import select, func
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
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        result = await self.session.execute(query)
        package = result.scalar_one_or_none()
        if package:
            package.consumed_at = datetime.utcnow()
            await self.session.flush()
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
