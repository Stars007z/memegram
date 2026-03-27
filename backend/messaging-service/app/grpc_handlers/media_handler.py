import uuid

import grpc

from app.container import Container
from app.generated import messaging_pb2
from app.grpc_handlers.conversation_handler import _set_error_from_value_error


class MediaHandler:

    def __init__(self, container: Container) -> None:
        self._container = container

    async def initiate_media_upload(self, request, context):
        if not request.user_id or not request.conversation_id or not request.mime_type:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id, conversation_id, and mime_type are required")
            return messaging_pb2.InitiateMediaUploadResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.media_service.initiate_upload(
                    user_id=uuid.UUID(request.user_id),
                    conversation_id=uuid.UUID(request.conversation_id),
                    mime_type=request.mime_type,
                    encrypted_size=request.encrypted_size,
                    encryption_metadata=request.encryption_metadata,
                )
                return messaging_pb2.InitiateMediaUploadResponse(
                    media_id=str(result.media_id),
                    upload_url=result.upload_url,
                    expires_in=result.expires_in,
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.InitiateMediaUploadResponse()

    async def confirm_media_upload(self, request, context):
        if not request.user_id or not request.media_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and media_id are required")
            return messaging_pb2.ConfirmMediaUploadResponse()

        async with self._container.request_scope() as scope:
            try:
                success = await scope.media_service.confirm_upload(
                    user_id=uuid.UUID(request.user_id),
                    media_id=uuid.UUID(request.media_id),
                )
                return messaging_pb2.ConfirmMediaUploadResponse(success=success)
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.ConfirmMediaUploadResponse()

    async def get_media_download_url(self, request, context):
        if not request.user_id or not request.media_id:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id and media_id are required")
            return messaging_pb2.GetMediaDownloadUrlResponse()

        async with self._container.request_scope() as scope:
            try:
                result = await scope.media_service.get_download_url(
                    user_id=uuid.UUID(request.user_id),
                    media_id=uuid.UUID(request.media_id),
                )
                return messaging_pb2.GetMediaDownloadUrlResponse(
                    download_url=result.download_url,
                    expires_in=result.expires_in,
                    encryption_metadata=result.encryption_metadata,
                )
            except ValueError as e:
                _set_error_from_value_error(context, e)
                return messaging_pb2.GetMediaDownloadUrlResponse()
