import uuid
from datetime import datetime
from typing import Sequence

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.media_object import MediaObject
from app.repositories.base import BaseRepository


class MediaObjectRepository(BaseRepository[MediaObject]):
    def __init__(self, session: AsyncSession):
        super().__init__(MediaObject, session)

    async def get_by_s3_key(self, s3_key: str) -> MediaObject | None:
        return await self.get_by_field("s3_key", s3_key)

    async def mark_uploaded(self, media_id: uuid.UUID) -> MediaObject | None:
        obj = await self.get_by_id(media_id)
        if obj is None:
            return None
        return await self.update(obj, {
            "status": "uploaded",
            "uploaded_at": datetime.utcnow(),
        })

    async def mark_deleted(self, media_id: uuid.UUID) -> MediaObject | None:
        obj = await self.get_by_id(media_id)
        if obj is None:
            return None
        return await self.update(obj, {
            "status": "deleted",
            "deleted_at": datetime.utcnow(),
        })

    async def mark_deleted_batch(self, media_ids: list[uuid.UUID]) -> int:
        if not media_ids:
            return 0
        stmt = (
            update(MediaObject)
            .where(MediaObject.id.in_(media_ids))
            .values(status="deleted", deleted_at=datetime.utcnow())
        )
        result = await self.session.execute(stmt)
        await self.session.flush()
        return result.rowcount

    async def get_expired(self, batch_size: int = 100) -> Sequence[MediaObject]:
        query = (
            select(MediaObject)
            .where(
                MediaObject.status == "uploaded",
                MediaObject.expires_at.isnot(None),
                MediaObject.expires_at < datetime.utcnow(),
            )
            .limit(batch_size)
        )
        result = await self.session.execute(query)
        return result.scalars().all()
