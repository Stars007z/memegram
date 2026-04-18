import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass
class UploadPresignedResult:
    upload_url: str
    s3_key: str
    expires_at: int


@dataclass
class DownloadPresignedResult:
    download_url: str
    expires_at: int


@dataclass
class VerifyResult:
    exists: bool
    actual_size: int


@dataclass
class BatchDeleteFailure:
    media_id: str
    error: str


@dataclass
class BatchDeleteResult:
    deleted_count: int
    failed: list[BatchDeleteFailure]


class IMediaObjectService(ABC):

    @abstractmethod
    async def get_upload_presigned_url(
        self,
        media_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mime_type: str,
        encrypted_size: int,
        expires_in_seconds: int,
    ) -> UploadPresignedResult: ...

    @abstractmethod
    async def verify_object_exists(
        self,
        media_id: uuid.UUID,
        s3_key: str,
    ) -> VerifyResult: ...

    @abstractmethod
    async def get_download_presigned_url(
        self,
        s3_key: str,
        expires_in_seconds: int,
    ) -> DownloadPresignedResult: ...

    @abstractmethod
    async def delete_object(
        self,
        media_id: uuid.UUID,
        s3_key: str,
    ) -> bool: ...

    @abstractmethod
    async def delete_objects_batch(
        self,
        objects: list[tuple[uuid.UUID, str]],
    ) -> BatchDeleteResult: ...

    @abstractmethod
    async def process_expired_objects(
        self,
        batch_size: int,
    ) -> int: ...
