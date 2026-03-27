from __future__ import annotations

import time
import uuid

from app.config import settings
from app.infrastructure.s3_client import S3Client
from app.repositories.media_object_repo import MediaObjectRepository
from app.services.interfaces.media_object_service import (
    BatchDeleteFailure,
    BatchDeleteResult,
    DownloadPresignedResult,
    IMediaObjectService,
    UploadPresignedResult,
    VerifyResult,
)


def _mime_prefix(mime_type: str) -> str:
    return mime_type.split("/")[0] if "/" in mime_type else "unknown"


class MediaObjectServiceImpl(IMediaObjectService):
    def __init__(
        self,
        repo: MediaObjectRepository,
        s3: S3Client,
    ) -> None:
        self._repo = repo
        self._s3 = s3

    async def get_upload_presigned_url(
        self,
        media_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mime_type: str,
        encrypted_size: int,
        expires_in_seconds: int,
    ) -> UploadPresignedResult:
        s3_key = f"media/{conversation_id}/{media_id}/{_mime_prefix(mime_type)}"
        bucket = settings.S3_BUCKET_NAME

        await self._repo.create({
            "id": media_id,
            "s3_bucket": bucket,
            "s3_key": s3_key,
            "mime_type": mime_type,
            "encrypted_size": encrypted_size,
            "status": "pending",
        })

        upload_url = await self._s3.generate_presigned_upload_url(
            bucket=bucket,
            key=s3_key,
            content_type=mime_type,
            content_length=encrypted_size,
            expires_in=expires_in_seconds,
        )

        expires_at = int(time.time()) + expires_in_seconds

        return UploadPresignedResult(
            upload_url=upload_url,
            s3_key=s3_key,
            expires_at=expires_at,
        )

    async def verify_object_exists(
        self,
        media_id: uuid.UUID,
        s3_key: str,
    ) -> VerifyResult:
        obj = await self._repo.get_by_id(media_id)
        if obj is None:
            return VerifyResult(exists=False, actual_size=0)

        head = await self._s3.head_object(
            bucket=obj.s3_bucket,
            key=s3_key,
        )
        if head is None:
            return VerifyResult(exists=False, actual_size=0)

        actual_size = head["content_length"]

        # ±1% tolerance on encrypted size
        if obj.encrypted_size > 0:
            diff = abs(actual_size - obj.encrypted_size) / obj.encrypted_size
            if diff > 0.01:
                return VerifyResult(exists=False, actual_size=actual_size)

        await self._repo.mark_uploaded(media_id)

        return VerifyResult(exists=True, actual_size=actual_size)

    async def get_download_presigned_url(
        self,
        s3_key: str,
        expires_in_seconds: int,
    ) -> DownloadPresignedResult:
        obj = await self._repo.get_by_s3_key(s3_key)
        if obj is None or obj.status != "uploaded":
            raise FileNotFoundError(f"Object with key {s3_key} not in uploaded state")

        download_url = await self._s3.generate_presigned_download_url(
            bucket=obj.s3_bucket,
            key=s3_key,
            expires_in=expires_in_seconds,
        )

        expires_at = int(time.time()) + expires_in_seconds

        return DownloadPresignedResult(
            download_url=download_url,
            expires_at=expires_at,
        )

    async def delete_object(
        self,
        media_id: uuid.UUID,
        s3_key: str,
    ) -> bool:
        obj = await self._repo.get_by_id(media_id)
        if obj is None:
            return False

        await self._s3.delete_object(bucket=obj.s3_bucket, key=s3_key)
        await self._repo.mark_deleted(media_id)
        return True

    async def delete_objects_batch(
        self,
        objects: list[tuple[uuid.UUID, str]],
    ) -> BatchDeleteResult:
        if not objects:
            return BatchDeleteResult(deleted_count=0, failed=[])

        keys = [s3_key for _, s3_key in objects]
        bucket = settings.S3_BUCKET_NAME

        deleted_count, errors = await self._s3.delete_objects(bucket, keys)

        error_keys = {e["key"] for e in errors}
        success_ids = [
            mid for mid, s3_key in objects if s3_key not in error_keys
        ]
        await self._repo.mark_deleted_batch(success_ids)

        failed = [
            BatchDeleteFailure(
                media_id=str(mid),
                error=next(
                    (e["message"] for e in errors if e["key"] == s3_key), "unknown",
                ),
            )
            for mid, s3_key in objects
            if s3_key in error_keys
        ]

        return BatchDeleteResult(deleted_count=deleted_count, failed=failed)

    async def process_expired_objects(self, batch_size: int) -> int:
        expired = await self._repo.get_expired(batch_size)
        if not expired:
            return 0

        objects = [(obj.id, obj.s3_key) for obj in expired]
        result = await self.delete_objects_batch(objects)
        return result.deleted_count
