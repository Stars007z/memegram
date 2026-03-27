from abc import ABC, abstractmethod
from dataclasses import dataclass, field
import uuid

import grpc

from app.generated import media_pb2, media_pb2_grpc


@dataclass
class PresignedUploadResult:
    upload_url: str
    s3_key: str
    expires_at: int


@dataclass
class PresignedDownloadResult:
    download_url: str
    expires_at: int


@dataclass
class BatchDeleteFailure:
    media_id: str
    error: str


@dataclass
class BatchDeleteResult:
    deleted_count: int
    failed: list[BatchDeleteFailure] = field(default_factory=list)


class IMediaClient(ABC):

    @abstractmethod
    async def get_upload_presigned_url(
        self,
        media_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mime_type: str,
        encrypted_size: int,
        expires_in_seconds: int = 3600,
    ) -> PresignedUploadResult:
        ...

    @abstractmethod
    async def verify_object_exists(
        self, media_id: uuid.UUID, s3_key: str,
    ) -> tuple[bool, int]:
        """Returns (exists, actual_size)."""
        ...

    @abstractmethod
    async def get_download_presigned_url(
        self, s3_key: str, expires_in_seconds: int = 900,
    ) -> PresignedDownloadResult:
        ...

    @abstractmethod
    async def delete_object(
        self, media_id: uuid.UUID, s3_key: str,
    ) -> bool:
        ...

    @abstractmethod
    async def delete_objects_batch(
        self, objects: list[tuple[uuid.UUID, str]],
    ) -> BatchDeleteResult:
        """objects is a list of (media_id, s3_key) tuples. Max 1000 per call."""
        ...

    @abstractmethod
    async def health_check(self) -> bool:
        ...


class GrpcMediaClient(IMediaClient):
    """Real gRPC client — used when media-service is deployed."""

    def __init__(self, channel: grpc.aio.Channel):
        self._stub = media_pb2_grpc.MediaServiceStub(channel)

    async def get_upload_presigned_url(
        self, media_id, conversation_id, mime_type, encrypted_size, expires_in_seconds=3600,
    ) -> PresignedUploadResult:
        response = await self._stub.GetUploadPresignedUrl(
            media_pb2.GetUploadPresignedUrlRequest(
                media_id=str(media_id),
                conversation_id=str(conversation_id),
                mime_type=mime_type,
                encrypted_size=encrypted_size,
                expires_in_seconds=expires_in_seconds,
            ),
            timeout=10,
        )
        return PresignedUploadResult(
            upload_url=response.upload_url,
            s3_key=response.s3_key,
            expires_at=response.expires_at,
        )

    async def verify_object_exists(self, media_id, s3_key):
        response = await self._stub.VerifyObjectExists(
            media_pb2.VerifyObjectExistsRequest(
                media_id=str(media_id), s3_key=s3_key,
            ),
            timeout=10,
        )
        return response.exists, response.actual_size

    async def get_download_presigned_url(self, s3_key, expires_in_seconds=900):
        response = await self._stub.GetDownloadPresignedUrl(
            media_pb2.GetDownloadPresignedUrlRequest(
                s3_key=s3_key, expires_in_seconds=expires_in_seconds,
            ),
            timeout=10,
        )
        return PresignedDownloadResult(
            download_url=response.download_url,
            expires_at=response.expires_at,
        )

    async def delete_object(self, media_id, s3_key):
        response = await self._stub.DeleteObject(
            media_pb2.DeleteObjectRequest(
                media_id=str(media_id), s3_key=s3_key,
            ),
            timeout=10,
        )
        return response.success

    async def delete_objects_batch(self, objects):
        response = await self._stub.DeleteObjectsBatch(
            media_pb2.DeleteObjectsBatchRequest(
                objects=[
                    media_pb2.DeleteObjectsBatchEntry(
                        media_id=str(mid), s3_key=key,
                    )
                    for mid, key in objects
                ],
            ),
            timeout=30,
        )
        return BatchDeleteResult(
            deleted_count=response.deleted_count,
            failed=[
                BatchDeleteFailure(media_id=f.media_id, error=f.error)
                for f in response.failed
            ],
        )

    async def health_check(self) -> bool:
        try:
            response = await self._stub.HealthCheck(
                media_pb2.MediaHealthCheckRequest(), timeout=5,
            )
            return response.status == "ok"
        except grpc.RpcError:
            return False
