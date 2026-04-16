import uuid
from datetime import datetime

import grpc

from app.config import settings
from app.logging_config import get_logger
from app.infrastructure.media_client import IMediaClient
from app.repositories.media_attachment_repo import MediaAttachmentRepository
from app.repositories.member_repo import MemberRepository
from app.services.interfaces.media_service import (
    DownloadUrlResult,
    IMediaService,
    UploadInitResult,
)

logger = get_logger(__name__)


class MediaServiceImpl(IMediaService):

    def __init__(
        self,
        attachment_repo: MediaAttachmentRepository,
        member_repo: MemberRepository,
        media_client: IMediaClient,
    ) -> None:
        self._attachments = attachment_repo
        self._members = member_repo
        self._media = media_client

    async def initiate_upload(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mime_type: str,
        encrypted_size: int,
        encryption_metadata: bytes,
    ) -> UploadInitResult:
        if not await self._members.is_member(conversation_id, user_id):
            raise ValueError("PERMISSION_DENIED: Not a member of this conversation")

        if encrypted_size > settings.MAX_UPLOAD_SIZE_BYTES:
            raise ValueError(
                f"INVALID_ARGUMENT: File too large (max {settings.MAX_UPLOAD_SIZE_BYTES} bytes)"
            )

        attachment = await self._attachments.create({
            "uploader_user_id": user_id,
            "conversation_id": conversation_id,
            "mime_type": mime_type,
            "encrypted_size": encrypted_size,
            "encryption_metadata": encryption_metadata,
        })

        result = await self._media.get_upload_presigned_url(
            media_id=attachment.id,
            conversation_id=conversation_id,
            mime_type=mime_type,
            encrypted_size=encrypted_size,
            expires_in_seconds=settings.PRESIGNED_UPLOAD_TTL,
        )

        await self._attachments.update(attachment, {"s3_key": result.s3_key})

        return UploadInitResult(
            media_id=attachment.id,
            upload_url=result.upload_url,
            expires_in=settings.PRESIGNED_UPLOAD_TTL,
        )

    async def confirm_upload(
        self,
        user_id: uuid.UUID,
        media_id: uuid.UUID,
    ) -> bool:
        attachment = await self._attachments.get_by_id(media_id)
        if not attachment:
            raise ValueError("NOT_FOUND: Media attachment not found")
        if attachment.uploader_user_id != user_id:
            raise ValueError("PERMISSION_DENIED: Not the uploader")

        if not attachment.s3_key:
            raise ValueError("FAILED_PRECONDITION: Upload was not initiated properly")

        exists, _ = await self._media.verify_object_exists(
            media_id=media_id, s3_key=attachment.s3_key,
        )
        if not exists:
            raise ValueError("FAILED_PRECONDITION: File not found in storage")

        await self._attachments.update(attachment, {
            "confirmed_at": datetime.utcnow(),
        })
        return True

    async def get_download_url(
        self,
        user_id: uuid.UUID,
        media_id: uuid.UUID,
    ) -> DownloadUrlResult:
        attachment = await self._attachments.get_by_id(media_id)
        if not attachment:
            raise ValueError("NOT_FOUND: Media attachment not found")

        if not await self._members.is_member(attachment.conversation_id, user_id):
            raise ValueError("PERMISSION_DENIED: Not a member of this conversation")

        if attachment.confirmed_at is None:
            raise ValueError("FAILED_PRECONDITION: Upload not confirmed")

        if not attachment.s3_key:
            raise ValueError("FAILED_PRECONDITION: Missing s3_key for attachment")

        result = await self._media.get_download_presigned_url(
            s3_key=attachment.s3_key,
            expires_in_seconds=settings.PRESIGNED_DOWNLOAD_TTL,
        )

        return DownloadUrlResult(
            download_url=result.download_url,
            expires_in=settings.PRESIGNED_DOWNLOAD_TTL,
            encryption_metadata=attachment.encryption_metadata,
        )

    async def delete_media(self, media_id: uuid.UUID) -> bool:
        attachment = await self._attachments.get_by_id(media_id)
        if not attachment or not attachment.s3_key:
            return False

        try:
            await self._media.delete_object(
                media_id=media_id, s3_key=attachment.s3_key,
            )
        except grpc.RpcError as e:
            logger.warning("media.s3_delete_failed", media_id=str(media_id), error=str(e))
            return False

        await self._attachments.update(attachment, {
            "confirmed_at": None,
            "s3_key": None,
        })
        return True
