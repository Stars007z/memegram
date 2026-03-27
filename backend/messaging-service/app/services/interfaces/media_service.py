from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional
import uuid


@dataclass
class UploadInitResult:
    media_id: uuid.UUID
    upload_url: str
    expires_in: int


@dataclass
class DownloadUrlResult:
    download_url: str
    expires_in: int
    encryption_metadata: bytes


class IMediaService(ABC):

    @abstractmethod
    async def initiate_upload(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mime_type: str,
        encrypted_size: int,
        encryption_metadata: bytes,
    ) -> UploadInitResult:
        ...

    @abstractmethod
    async def confirm_upload(
        self,
        user_id: uuid.UUID,
        media_id: uuid.UUID,
    ) -> bool:
        ...

    @abstractmethod
    async def get_download_url(
        self,
        user_id: uuid.UUID,
        media_id: uuid.UUID,
    ) -> DownloadUrlResult:
        ...

    @abstractmethod
    async def delete_media(self, media_id: uuid.UUID) -> bool:
        """Delete a single media object from S3 and mark attachment as deleted."""
        ...
