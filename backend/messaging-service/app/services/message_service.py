import uuid
from datetime import datetime
from typing import Optional

import redis.asyncio as aioredis

from app.infrastructure.contacts_client import IContactsClient
from app.logging_config import get_logger
from app.repositories.conversation_repo import ConversationRepository
from app.repositories.member_repo import MemberRepository
from app.repositories.message_repo import MessageRepository
from app.services.interfaces.media_service import IMediaService
from app.services.interfaces.message_service import IMessageService, MessageListResult, MessageResult, SendResult
from app.services.interfaces.stream_service import IStreamService

logger = get_logger(__name__)


class MessageServiceImpl(IMessageService):

    def __init__(
        self,
        message_repo: MessageRepository,
        member_repo: MemberRepository,
        conversation_repo: ConversationRepository,
        redis: aioredis.Redis,
        stream_service: IStreamService,
        media_service: IMediaService,
        contacts_client: IContactsClient,
    ) -> None:
        self._messages = message_repo
        self._members = member_repo
        self._conversations = conversation_repo
        self._redis = redis
        self._stream = stream_service
        self._media = media_service
        self._contacts = contacts_client

    async def send_message(
        self,
        sender_user_id: uuid.UUID,
        sender_device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        mls_ciphertext: bytes,
        type: str,
        client_message_id: uuid.UUID,
        media_id: Optional[uuid.UUID] = None,
        reply_to_message_id: Optional[uuid.UUID] = None,
    ) -> SendResult:
        if not await self._members.is_member(conversation_id, sender_user_id):
            raise ValueError("PERMISSION_DENIED: Not a member of this conversation")

        conv = await self._conversations.get_by_id(conversation_id)
        if conv is not None and conv.type == "direct":
            members = await self._members.get_active_members(conversation_id)
            peer = next((m for m in members if m.user_id != sender_user_id), None)
            if peer is None:
                raise ValueError("FAILED_PRECONDITION: Recipient is not available")
            if await self._contacts.is_blocked(peer.user_id, sender_user_id):
                raise ValueError("PERMISSION_DENIED: You are blocked by this user")
            if await self._contacts.is_blocked(sender_user_id, peer.user_id):
                raise ValueError("PERMISSION_DENIED: You have blocked this user")

        existing = await self._messages.get_by_client_message_id(client_message_id)
        if existing:
            return SendResult(
                message_id=existing.id,
                created_at=existing.created_at.timestamp(),
            )

        msg = await self._messages.create(
            {
                "conversation_id": conversation_id,
                "sender_user_id": sender_user_id,
                "sender_device_id": sender_device_id,
                "type": type,
                "mls_ciphertext": mls_ciphertext,
                "media_id": media_id,
                "reply_to_message_id": reply_to_message_id,
                "client_message_id": client_message_id,
            }
        )

        await self._conversations.update_last_message(conversation_id, msg.id)

        await self._increment_unread_for_others(conversation_id, sender_user_id)

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "new_message",
                "message": self._msg_to_dict(msg),
                "conversation_type": getattr(conv, "type", "") if conv is not None else "",
                "conversation_name": getattr(conv, "name", "") or "",
                "avatar_media_id": str(getattr(conv, "avatar_media_id", "") or ""),
                "sender_user_id": str(sender_user_id),
                "message_type": type,
                "created_at": msg.created_at.timestamp(),
            },
        )

        logger.info(
            "message.sent",
            message_id=str(msg.id),
            conversation_id=str(conversation_id),
            sender_user_id=str(sender_user_id),
            type=type,
        )

        return SendResult(message_id=msg.id, created_at=msg.created_at.timestamp())

    async def get_messages(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        limit: int,
        before_message_id: Optional[uuid.UUID] = None,
    ) -> MessageListResult:
        if not await self._members.is_member(conversation_id, user_id):
            raise ValueError("PERMISSION_DENIED: Not a member of this conversation")

        limit = min(limit, 100)
        rows = await self._messages.get_messages_before(
            conversation_id,
            before_message_id,
            limit,
        )

        has_more = len(rows) > limit
        messages = rows[:limit]

        return MessageListResult(
            messages=[self._to_result(m) for m in messages],
            has_more=has_more,
        )

    async def edit_message(
        self,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        new_mls_ciphertext: bytes,
    ) -> MessageResult:
        msg = await self._messages.get_by_id(message_id)
        if not msg:
            raise ValueError("NOT_FOUND: Message not found")
        if msg.sender_user_id != user_id:
            raise ValueError("PERMISSION_DENIED: Can only edit own messages")
        if msg.deleted_at is not None:
            raise ValueError("NOT_FOUND: Message has been deleted")

        now = datetime.utcnow()
        await self._messages.update(
            msg,
            {
                "mls_ciphertext": new_mls_ciphertext,
                "edited_at": now,
            },
        )

        await self._stream.publish_event(
            msg.conversation_id,
            {
                "event_type": "message_edited",
                "message_id": str(msg.id),
                "new_mls_ciphertext": new_mls_ciphertext.hex(),
                "edited_at": now.timestamp(),
            },
        )

        return self._to_result(msg)

    async def delete_message(
        self,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        delete_for_everyone: bool,
    ) -> bool:
        msg = await self._messages.get_by_id(message_id)
        if not msg:
            raise ValueError("NOT_FOUND: Message not found")

        if delete_for_everyone:
            is_sender = msg.sender_user_id == user_id
            is_admin = await self._members.has_role(
                msg.conversation_id,
                user_id,
                ["owner", "admin"],
            )
            if not is_sender and not is_admin:
                raise ValueError("PERMISSION_DENIED: Cannot delete this message")

        await self._messages.update(
            msg,
            {
                "deleted_at": datetime.utcnow(),
                "mls_ciphertext": b"",
            },
        )

        if msg.media_id:
            try:
                await self._media.delete_media(msg.media_id)
            except Exception:
                logger.warning(
                    "media.delete_failed",
                    media_id=str(msg.media_id),
                    message_id=str(msg.id),
                )

        logger.info(
            "message.deleted",
            message_id=str(msg.id),
            conversation_id=str(msg.conversation_id),
            user_id=str(user_id),
            delete_for_everyone=delete_for_everyone,
        )

        await self._stream.publish_event(
            msg.conversation_id,
            {
                "event_type": "message_deleted",
                "message_id": str(msg.id),
            },
        )

        return True

    async def mark_as_read(
        self,
        user_id: uuid.UUID,
        conversation_id: uuid.UUID,
        last_read_message_id: uuid.UUID,
    ) -> int:
        member = await self._members.get_active_member(conversation_id, user_id)
        if not member:
            raise ValueError("NOT_FOUND: Not a member of this conversation")

        await self._members.update(
            member,
            {
                "last_read_message_id": last_read_message_id,
            },
        )

        key = f"unread:{user_id}:{conversation_id}"
        await self._redis.delete(key)

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "message_read",
                "user_id": str(user_id),
                "last_read_message_id": str(last_read_message_id),
                "read_at": datetime.utcnow().timestamp(),
            },
        )
        return 0

    async def _increment_unread_for_others(
        self,
        conversation_id: uuid.UUID,
        sender_user_id: uuid.UUID,
    ) -> None:
        members = await self._members.get_active_members(conversation_id)
        for m in members:
            if m.user_id == sender_user_id:
                continue

            try:
                if await self._contacts.is_blocked(m.user_id, sender_user_id):
                    logger.debug(
                        "message.unread.skipped_blocked_sender",
                        conversation_id=str(conversation_id),
                        recipient_user_id=str(m.user_id),
                        sender_user_id=str(sender_user_id),
                    )
                    continue
            except Exception as exc:
                logger.warning(
                    "message.unread.block_lookup_failed",
                    conversation_id=str(conversation_id),
                    recipient_user_id=str(m.user_id),
                    sender_user_id=str(sender_user_id),
                    error=str(exc),
                )

            key = f"unread:{m.user_id}:{conversation_id}"
            await self._redis.incr(key)

    @staticmethod
    def _to_result(msg) -> MessageResult:
        return MessageResult(
            id=msg.id,
            sender_user_id=msg.sender_user_id,
            sender_device_id=msg.sender_device_id,
            type=msg.type,
            mls_ciphertext=msg.mls_ciphertext,
            media_id=msg.media_id,
            reply_to_message_id=msg.reply_to_message_id,
            mls_epoch=msg.mls_epoch,
            created_at=msg.created_at.timestamp(),
            edited_at=msg.edited_at.timestamp() if msg.edited_at else None,
            deleted_at=msg.deleted_at.timestamp() if msg.deleted_at else None,
        )

    @staticmethod
    def _msg_to_dict(msg) -> dict:
        return {
            "id": str(msg.id),
            "sender_user_id": str(msg.sender_user_id),
            "sender_device_id": str(msg.sender_device_id),
            "type": msg.type,
            "mls_ciphertext": msg.mls_ciphertext.hex(),
            "media_id": str(msg.media_id) if msg.media_id else "",
            "reply_to_message_id": str(msg.reply_to_message_id) if msg.reply_to_message_id else "",
            "mls_epoch": msg.mls_epoch or 0,
            "created_at": msg.created_at.timestamp(),
            "edited_at": msg.edited_at.timestamp() if msg.edited_at else 0,
            "deleted_at": msg.deleted_at.timestamp() if msg.deleted_at else 0,
        }
