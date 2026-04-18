import uuid
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.media_attachment import MediaAttachment
from app.repositories.base import BaseRepository

class MediaAttachmentRepository(BaseRepository[MediaAttachment]):
    def __init__(self, session: AsyncSession):
        super().__init__(MediaAttachment, session)

    async def get_confirmed(self, media_id: uuid.UUID) -> Optional[MediaAttachment]:
        attachment = await self.get_by_id(media_id)
        if attachment and attachment.confirmed_at is not None:
            return attachment
        return None
