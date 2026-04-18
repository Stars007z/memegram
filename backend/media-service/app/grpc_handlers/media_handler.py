import uuid

import grpc

from app.config import settings
from app.container import Container
from app.generated import media_pb2, media_pb2_grpc


class MediaServiceHandler(media_pb2_grpc.MediaServiceServicer):

    def __init__(self, container: Container) -> None:
        self._container = container

    async def GetUploadPresignedUrl(self, request, context):
        if not request.media_id or not request.conversation_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("media_id and conversation_id are required")
            return media_pb2.GetUploadPresignedUrlResponse()

        if request.encrypted_size > settings.MAX_UPLOAD_SIZE_BYTES:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(
                f"encrypted_size exceeds max {settings.MAX_UPLOAD_SIZE_BYTES} bytes",
            )
            return media_pb2.GetUploadPresignedUrlResponse()

        expires_in = request.expires_in_seconds or settings.PRESIGNED_UPLOAD_TTL

        async with self._container.request_scope() as scope:
            result = await scope.media_object_service.get_upload_presigned_url(
                media_id=uuid.UUID(request.media_id),
                conversation_id=uuid.UUID(request.conversation_id),
                mime_type=request.mime_type,
                encrypted_size=request.encrypted_size,
                expires_in_seconds=expires_in,
            )

        return media_pb2.GetUploadPresignedUrlResponse(
            upload_url=result.upload_url,
            s3_key=result.s3_key,
            expires_at=result.expires_at,
        )

    async def VerifyObjectExists(self, request, context):
        if not request.media_id or not request.s3_key:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("media_id and s3_key are required")
            return media_pb2.VerifyObjectExistsResponse()

        async with self._container.request_scope() as scope:
            result = await scope.media_object_service.verify_object_exists(
                media_id=uuid.UUID(request.media_id),
                s3_key=request.s3_key,
            )

        if not result.exists and result.actual_size == 0:
            context.set_code(grpc.StatusCode.NOT_FOUND)
            context.set_details("Object not found in S3")

        return media_pb2.VerifyObjectExistsResponse(
            exists=result.exists,
            actual_size=result.actual_size,
        )

    async def GetDownloadPresignedUrl(self, request, context):
        if not request.s3_key:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("s3_key is required")
            return media_pb2.GetDownloadPresignedUrlResponse()

        expires_in = request.expires_in_seconds or settings.PRESIGNED_DOWNLOAD_TTL

        async with self._container.request_scope() as scope:
            try:
                result = await scope.media_object_service.get_download_presigned_url(
                    s3_key=request.s3_key,
                    expires_in_seconds=expires_in,
                )
            except FileNotFoundError:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details("Object not in uploaded state")
                return media_pb2.GetDownloadPresignedUrlResponse()

        return media_pb2.GetDownloadPresignedUrlResponse(
            download_url=result.download_url,
            expires_at=result.expires_at,
        )

    async def DeleteObject(self, request, context):
        if not request.media_id or not request.s3_key:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("media_id and s3_key are required")
            return media_pb2.DeleteObjectResponse()

        async with self._container.request_scope() as scope:
            success = await scope.media_object_service.delete_object(
                media_id=uuid.UUID(request.media_id),
                s3_key=request.s3_key,
            )

        return media_pb2.DeleteObjectResponse(success=success)

    async def DeleteObjectsBatch(self, request, context):
        if len(request.objects) > 1000:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("Maximum 1000 objects per batch")
            return media_pb2.DeleteObjectsBatchResponse()

        objects = [(uuid.UUID(item.media_id), item.s3_key) for item in request.objects]

        async with self._container.request_scope() as scope:
            result = await scope.media_object_service.delete_objects_batch(objects)

        return media_pb2.DeleteObjectsBatchResponse(
            deleted_count=result.deleted_count,
            failed=[media_pb2.BatchDeleteError(media_id=f.media_id, error=f.error) for f in result.failed],
        )

    async def ProcessExpiredObjects(self, request, context):
        batch_size = request.batch_size or 100

        async with self._container.request_scope() as scope:
            deleted_count = await scope.media_object_service.process_expired_objects(
                batch_size=batch_size,
            )

        return media_pb2.ProcessExpiredObjectsResponse(deleted_count=deleted_count)

    async def HealthCheck(self, request, context):
        db_status = "unknown"
        s3_status = "unknown"

        try:
            async with self._container.request_scope() as scope:
                from sqlalchemy import text

                await scope._session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {e}"

        try:
            ok = await self._container._s3.head_bucket(settings.S3_BUCKET_NAME)
            s3_status = "connected" if ok else "disconnected"
        except Exception as e:
            s3_status = f"failed: {e}"

        overall = "ok"
        if "failed" in db_status or "failed" in s3_status:
            overall = "degraded"

        return media_pb2.MediaHealthCheckResponse(
            status=overall,
            db_status=db_status,
            s3_status=s3_status,
            version=settings.SERVICE_VERSION,
        )
