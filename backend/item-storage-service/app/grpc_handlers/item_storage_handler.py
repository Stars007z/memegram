import grpc

from app.config import settings
from app.generated import item_storage_pb2, item_storage_pb2_grpc
from app.infrastructure import s3_client
from app.services.item_storage_service import ItemStorageService


class ItemStorageHandler(item_storage_pb2_grpc.ItemStorageServiceServicer):
    def __init__(self, get_session):
        self.get_session = get_session

    async def InitiateUpload(self, request, context):
        if not request.owner_user_id or not request.item_type or not request.mime_type:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("owner_user_id, item_type, and mime_type are required")
            return item_storage_pb2.InitiateUploadResponse()

        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                item_id, upload_url, expires_at = await service.initiate_upload(
                    owner_user_id=request.owner_user_id,
                    item_type=request.item_type,
                    mime_type=request.mime_type,
                    size_bytes=request.size_bytes,
                )
                return item_storage_pb2.InitiateUploadResponse(
                    item_id=item_id,
                    upload_url=upload_url,
                    expires_at=expires_at,
                )
            except ValueError as e:
                context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
                context.set_details(str(e))
                return item_storage_pb2.InitiateUploadResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.InitiateUploadResponse()

    async def ConfirmUpload(self, request, context):
        if not request.owner_user_id or not request.item_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("owner_user_id and item_id are required")
            return item_storage_pb2.ConfirmUploadResponse()

        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                success = await service.confirm_upload(
                    owner_user_id=request.owner_user_id,
                    item_id=request.item_id,
                )
                return item_storage_pb2.ConfirmUploadResponse(success=success)
            except FileNotFoundError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return item_storage_pb2.ConfirmUploadResponse(success=False)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return item_storage_pb2.ConfirmUploadResponse(success=False)
            except ValueError as e:
                context.set_code(grpc.StatusCode.FAILED_PRECONDITION)
                context.set_details(str(e))
                return item_storage_pb2.ConfirmUploadResponse(success=False)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.ConfirmUploadResponse(success=False)

    async def GetDownloadUrl(self, request, context):
        if not request.item_id or not request.requester_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("item_id and requester_user_id are required")
            return item_storage_pb2.GetDownloadUrlResponse()

        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                download_url, expires_at, mime_type = await service.get_download_url(
                    item_id=request.item_id,
                    requester_user_id=request.requester_user_id,
                )
                return item_storage_pb2.GetDownloadUrlResponse(
                    download_url=download_url,
                    expires_at=expires_at,
                    mime_type=mime_type,
                )
            except FileNotFoundError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return item_storage_pb2.GetDownloadUrlResponse()
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return item_storage_pb2.GetDownloadUrlResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.GetDownloadUrlResponse()

    async def DeleteItem(self, request, context):
        if not request.owner_user_id or not request.item_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("owner_user_id and item_id are required")
            return item_storage_pb2.DeleteItemResponse(success=False)

        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                success = await service.delete_item(
                    owner_user_id=request.owner_user_id,
                    item_id=request.item_id,
                )
                return item_storage_pb2.DeleteItemResponse(success=success)
            except FileNotFoundError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return item_storage_pb2.DeleteItemResponse(success=False)
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return item_storage_pb2.DeleteItemResponse(success=False)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.DeleteItemResponse(success=False)

    async def DeleteUserItems(self, request, context):
        if not request.owner_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("owner_user_id is required")
            return item_storage_pb2.DeleteUserItemsResponse()

        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                item_types = list(request.item_types) if request.item_types else None
                deleted_count = await service.delete_user_items(
                    owner_user_id=request.owner_user_id,
                    item_types=item_types,
                )
                return item_storage_pb2.DeleteUserItemsResponse(deleted_count=deleted_count)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.DeleteUserItemsResponse()

    async def CleanupPendingUploads(self, request, context):
        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                older_than = request.older_than_seconds or settings.PENDING_CLEANUP_AFTER_SECONDS
                batch_size = request.batch_size or 100
                cleaned = await service.cleanup_pending_uploads(
                    older_than_seconds=older_than,
                    batch_size=batch_size,
                )
                return item_storage_pb2.CleanupPendingUploadsResponse(cleaned_count=cleaned)
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.CleanupPendingUploadsResponse()

    async def GetItemMetadata(self, request, context):
        if not request.item_id or not request.requester_user_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("item_id and requester_user_id are required")
            return item_storage_pb2.GetItemMetadataResponse()

        async with self.get_session() as session:
            service = ItemStorageService(session)
            try:
                item = await service.get_item_metadata(
                    item_id=request.item_id,
                    requester_user_id=request.requester_user_id,
                )
                return item_storage_pb2.GetItemMetadataResponse(
                    item_id=str(item.id),
                    owner_user_id=str(item.owner_user_id),
                    item_type=item.item_type,
                    mime_type=item.mime_type,
                    size_bytes=item.size_bytes,
                    uploaded_at=int(item.uploaded_at.timestamp()) if item.uploaded_at else 0,
                    access_policy=item.access_policy,
                )
            except FileNotFoundError as e:
                context.set_code(grpc.StatusCode.NOT_FOUND)
                context.set_details(str(e))
                return item_storage_pb2.GetItemMetadataResponse()
            except PermissionError as e:
                context.set_code(grpc.StatusCode.PERMISSION_DENIED)
                context.set_details(str(e))
                return item_storage_pb2.GetItemMetadataResponse()
            except Exception as e:
                context.set_code(grpc.StatusCode.INTERNAL)
                context.set_details(f"Internal error: {e}")
                return item_storage_pb2.GetItemMetadataResponse()

    async def HealthCheck(self, request, context):
        db_status = "unknown"
        s3_status = "unknown"

        try:
            async with self.get_session() as session:
                from sqlalchemy import text

                await session.execute(text("SELECT 1"))
                db_status = "connected"
        except Exception as e:
            db_status = f"failed: {e}"

        try:
            ok = await s3_client.head_bucket(settings.S3_BUCKET_NAME)
            s3_status = "connected" if ok else "failed"
        except Exception as e:
            s3_status = f"failed: {e}"

        overall = "ok" if db_status == "connected" and s3_status == "connected" else "degraded"
        return item_storage_pb2.HealthCheckResponse(
            status=overall,
            db_status=db_status,
            s3_status=s3_status,
            version=settings.SERVICE_VERSION,
        )
