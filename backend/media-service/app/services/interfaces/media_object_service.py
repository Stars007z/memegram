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
    async def delete_object_by_media_id(
        self,
        media_id: uuid.UUID,
    ) -> bool:
        """Delete an object knowing only its media_id.

        Looks up the s3_key from the repo, deletes the S3 object, marks the
        DB row as deleted. Idempotent: returns False if the object is unknown
        or already deleted.
        """
        ...

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
