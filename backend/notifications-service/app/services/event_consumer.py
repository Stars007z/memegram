"""Redis Streams event consumer for notifications.

Reads events from ``notifications:events`` stream published by messaging-service,
enriches them with metadata, and dispatches push notifications.
"""

from __future__ import annotations

import asyncio
import json
import uuid
from typing import Any

import redis.asyncio as aioredis

from app.config import settings
from app.database.session import get_session
from app.infrastructure.contacts_client import IContactsClient
from app.infrastructure.item_storage_client import IItemStorageClient
from app.infrastructure.messaging_client import IMessagingClient
from app.infrastructure.user_client import IUserClient
from app.logging_config import get_logger
from app.repositories.device_push_token_repo import DevicePushTokenRepository
from app.services.push_sender import (
    ApnsSender,
    FcmSender,
    IPushSender,
    PushErrorType,
    PushPayload,
    PushPlatform,
    send_with_retry,
)

logger = get_logger(__name__)

MESSAGE_TYPE_LABELS: dict[str, str] = {
    "text": "Новое сообщение",
    "image": "Фото",
    "video": "Видео",
    "audio": "Голосовое сообщение",
    "file": "Файл",
}


class EventConsumer:
    """Consumes ``notifications:events`` Redis Stream and dispatches push."""

    def __init__(
        self,
        messaging_redis: aioredis.Redis,
        own_redis: aioredis.Redis,
        messaging_client: IMessagingClient,
        user_client: IUserClient,
        item_storage_client: IItemStorageClient,
        contacts_client: IContactsClient,
    ) -> None:
        self._messaging_redis = messaging_redis
        self._own_redis = own_redis
        self._messaging_client = messaging_client
        self._user_client = user_client
        self._item_storage_client = item_storage_client
        self._contacts_client = contacts_client

        self._fcm_sender: IPushSender = FcmSender()
        self._apns_sender: IPushSender = ApnsSender()

        self._stream = settings.STREAM_NAME
        self._group = settings.CONSUMER_GROUP
        self._consumer = f"notifications-{uuid.uuid4().hex[:8]}"
        self._running = False

    async def start(self) -> None:
        """Create consumer group (if needed) and start consuming."""
        await self._ensure_consumer_group()

        self._running = True
        logger.info("event_consumer.started", consumer=self._consumer)
        await asyncio.gather(
            self._consume_loop(),
            self._claim_loop(),
        )

    async def _ensure_consumer_group(self) -> None:
        """Create the stream consumer group if Redis lost it after restart."""
        try:
            await self._messaging_redis.xgroup_create(
                self._stream,
                self._group,
                id="0",
                mkstream=True,
            )
            logger.info("consumer_group.created", group=self._group, stream=self._stream)
        except Exception as e:
            if "BUSYGROUP" not in str(e):
                logger.warning(
                    "consumer_group.ensure_failed",
                    group=self._group,
                    stream=self._stream,
                    error=str(e),
                )

    @staticmethod
    def _is_missing_group_error(error: Exception) -> bool:
        return "NOGROUP" in str(error).upper()

    async def stop(self) -> None:
        self._running = False

    async def _consume_loop(self) -> None:
        while self._running:
            try:
                messages = await self._messaging_redis.xreadgroup(
                    groupname=self._group,
                    consumername=self._consumer,
                    streams={self._stream: ">"},
                    count=10,
                    block=2000,
                )
                if not messages:
                    continue

                for stream_name, entries in messages:
                    for entry_id, fields in entries:
                        await self._process_entry(entry_id, fields)

            except Exception as e:
                if self._is_missing_group_error(e):
                    await self._ensure_consumer_group()
                logger.error("event_consumer.loop_error", error=str(e))
                await asyncio.sleep(1)

    async def _claim_loop(self) -> None:
        """Periodically claim messages stuck in pending for >60s."""
        while self._running:
            await asyncio.sleep(30)
            try:
                result = await self._messaging_redis.xautoclaim(
                    name=self._stream,
                    groupname=self._group,
                    consumername=self._consumer,
                    min_idle_time=60000,
                    start_id="0",
                    count=10,
                )

                if result and len(result) > 1:
                    claimed = result[1]
                    for entry_id, fields in claimed:
                        await self._process_entry(entry_id, fields)
            except Exception as e:
                if self._is_missing_group_error(e):
                    await self._ensure_consumer_group()
                logger.error("event_consumer.claim_error", error=str(e))

    async def _process_entry(self, entry_id: bytes | str, fields: dict) -> None:
        entry_id_str = entry_id.decode() if isinstance(entry_id, bytes) else entry_id
        try:

            decoded: dict[str, str] = {}
            for k, v in fields.items():
                key = k.decode() if isinstance(k, bytes) else k
                val = v.decode() if isinstance(v, bytes) else v
                decoded[key] = val

            event_type = decoded.get("type", "")
            payload_raw = decoded.get("payload", "{}")
            payload = json.loads(payload_raw)

            logger.info(
                "event_consumer.event_received",
                entry_id=entry_id_str,
                event_type=event_type,
                conversation_id=payload.get("conversation_id", ""),
            )

            dedup_key = f"notif:dedup:{entry_id_str}"
            was_set = await self._own_redis.set(dedup_key, "1", nx=True, ex=3600)
            if not was_set:
                logger.debug("event_consumer.duplicate_skipped", entry_id=entry_id_str)
                await self._messaging_redis.xack(self._stream, self._group, entry_id)
                return

            if event_type == "new_message":
                await self._handle_new_message(payload)
            elif event_type == "member_added":
                await self._handle_member_added(payload)
            elif event_type == "member_kicked":
                await self._handle_member_kicked(payload)
            elif event_type == "conversation_deleted":
                await self._handle_conversation_deleted(payload)
            else:
                logger.warning("event_consumer.unknown_event_type", event_type=event_type)

            await self._messaging_redis.xack(self._stream, self._group, entry_id)

        except Exception as e:
            logger.error("event_consumer.process_failed", entry_id=entry_id_str, error=str(e))

    async def _handle_new_message(self, event: dict) -> None:
        conversation_id = event.get("conversation_id", "")
        conversation_type = event.get("conversation_type", "")
        conversation_name = event.get("conversation_name", "")
        sender_user_id = event.get("sender_user_id", "")
        message_type = event.get("message_type", "text")
        avatar_media_id = event.get("avatar_media_id", "")
        created_at = event.get("created_at", "")

        members = await self._get_members_cached(conversation_id)
        recipient_ids = [m.user_id for m in members if m.user_id != sender_user_id]
        if not recipient_ids:
            return

        if sender_user_id:
            recipient_ids = await self._filter_blocked_recipients(
                recipient_ids,
                sender_user_id,
            )
            if not recipient_ids:
                logger.debug(
                    "push.all_recipients_blocked_sender",
                    sender_user_id=sender_user_id,
                    conversation_id=conversation_id,
                )
                return

        sender_info = await self._get_user_cached(sender_user_id)
        sender_name = sender_info.display_name if sender_info else "Unknown"
        sender_avatar_media_id = sender_info.avatar_media_id if sender_info else ""

        effective_avatar_media_id = avatar_media_id if conversation_type == "group" else sender_avatar_media_id
        avatar_url = await self._get_avatar_url_cached(effective_avatar_media_id) if effective_avatar_media_id else None

        type_label = MESSAGE_TYPE_LABELS.get(message_type, "Новое сообщение")
        if conversation_type == "group":
            title = conversation_name or "Группа"
            body = f"{sender_name}: {type_label}"
        else:
            title = sender_name
            body = type_label

        data = {
            "event_type": "new_message",
            "conversation_id": conversation_id,
            "conversation_type": conversation_type,
            "conversation_name": conversation_name,
            "sender_user_id": sender_user_id,
            "sender_name": sender_name,
            "message_type": message_type,
            "timestamp": created_at,
        }

        await self._send_push_to_users(
            recipient_user_ids=recipient_ids,
            title=title,
            body=body,
            data=data,
            thread_id=conversation_id,
            avatar_url=avatar_url,
            event_type="new_message",
            conversation_id=conversation_id,
        )

    async def _handle_member_added(self, event: dict) -> None:
        conversation_id = event.get("conversation_id", "")
        conversation_name = event.get("conversation_name", "Группа")
        avatar_media_id = event.get("avatar_media_id", "")
        added_user_ids = event.get("added_user_ids", [])

        if not added_user_ids:
            return

        avatar_url = await self._get_avatar_url_cached(avatar_media_id) if avatar_media_id else None

        await self._send_push_to_users(
            recipient_user_ids=added_user_ids,
            title=conversation_name,
            body="Вас добавили в группу",
            data={
                "event_type": "member_added",
                "conversation_id": conversation_id,
                "conversation_name": conversation_name,
            },
            thread_id=conversation_id,
            avatar_url=avatar_url,
            event_type="member_added",
            conversation_id=conversation_id,
        )

    async def _handle_member_kicked(self, event: dict) -> None:
        conversation_id = event.get("conversation_id", "")
        conversation_name = event.get("conversation_name", "Группа")
        kicked_user_id = event.get("kicked_user_id", "")

        if not kicked_user_id:
            return

        await self._send_push_to_users(
            recipient_user_ids=[kicked_user_id],
            title=conversation_name,
            body="Вас удалили из группы",
            data={
                "event_type": "member_kicked",
                "conversation_id": conversation_id,
                "conversation_name": conversation_name,
            },
            thread_id=conversation_id,
            avatar_url=None,
            event_type="member_kicked",
            conversation_id=conversation_id,
        )

    async def _handle_conversation_deleted(self, event: dict) -> None:
        """Silent push so clients can drop a conversation from local state."""
        conversation_id = event.get("conversation_id", "")
        deleted_by = event.get("deleted_by", "")
        member_user_ids = event.get("member_user_ids", []) or []
        reason = event.get("reason", "")
        conversation_type = event.get("conversation_type", "")

        recipient_ids = [uid for uid in member_user_ids if uid and uid != deleted_by]
        if not recipient_ids:
            return

        data: dict[str, str] = {
            "event_type": "conversation_deleted",
            "conversation_id": conversation_id,
        }
        if reason:
            data["reason"] = reason
        if conversation_type:
            data["conversation_type"] = conversation_type

        await self._send_push_to_users(
            recipient_user_ids=recipient_ids,
            title="",
            body="",
            data=data,
            thread_id=conversation_id,
            avatar_url=None,
            event_type="conversation_deleted",
            conversation_id=conversation_id,
        )

    async def _send_push_to_users(
        self,
        recipient_user_ids: list[str],
        title: str,
        body: str,
        data: dict[str, str],
        thread_id: str | None,
        avatar_url: str | None,
        event_type: str,
        conversation_id: str,
    ) -> None:
        async with get_session() as session:
            repo = DevicePushTokenRepository(session)
            tokens = await repo.get_active_tokens_for_users(
                [uuid.UUID(uid) for uid in recipient_user_ids],
            )

            if not tokens:
                logger.info(
                    "push.no_active_tokens",
                    event_type=event_type,
                    conversation_id=conversation_id,
                    recipient_count=len(recipient_user_ids),
                )
                return

            logger.info(
                "push.dispatching",
                event_type=event_type,
                conversation_id=conversation_id,
                recipient_count=len(recipient_user_ids),
                token_count=len(tokens),
                platforms=[t.platform for t in tokens],
            )

            tasks = []
            for token in tokens:
                platform = PushPlatform(token.platform)
                sender = self._fcm_sender if platform == PushPlatform.ANDROID else self._apns_sender

                payload = PushPayload(
                    token=token.push_token,
                    platform=platform,
                    title=title,
                    body=body,
                    data=data,
                    thread_id=thread_id,
                    avatar_url=avatar_url,
                )

                tasks.append(self._send_and_handle(sender, payload, token.id, repo))

            await asyncio.gather(*tasks, return_exceptions=True)

    async def _send_and_handle(
        self,
        sender: IPushSender,
        payload: PushPayload,
        token_id: uuid.UUID,
        repo: DevicePushTokenRepository,
    ) -> None:
        result = await send_with_retry(sender, payload)
        if result.success:
            payload_data = getattr(payload, "data", None) or {}
            platform_attr = getattr(payload, "platform", None)
            logger.info(
                "push.sent",
                token_id=str(token_id),
                platform=getattr(platform_attr, "value", str(platform_attr) if platform_attr else ""),
                event_type=payload_data.get("event_type", ""),
                conversation_id=payload_data.get("conversation_id", ""),
            )
            await repo.mark_success(token_id)
        elif result.error_type == PushErrorType.PERMANENT_TOKEN:
            logger.info("push.token_deactivated", token_id=str(token_id), error_code=result.error_code)
            await repo.deactivate_token(token_id)
        else:
            logger.warning("push.send_failed", token_id=str(token_id), error_code=result.error_code)
            await repo.increment_failure(
                token_id,
                max_failures=settings.MAX_TOKEN_CONSECUTIVE_FAILURES,
            )

    async def _filter_blocked_recipients(
        self,
        recipient_ids: list[str],
        sender_user_id: str,
    ) -> list[str]:
        """Return subset of recipient_ids that did NOT block the sender.

        Result is cached per (recipient, sender) pair for 60s to avoid bursts of
        gRPC calls during chat storms. Fail-open via GrpcContactsClient.
        """

        async def _check(recipient_id: str) -> tuple[str, bool]:
            cache_key = f"notif:blocked:{recipient_id}:{sender_user_id}"
            cached = await self._own_redis.get(cache_key)
            if cached is not None:
                value = cached.decode() if isinstance(cached, bytes) else cached
                return recipient_id, value == "1"

            blocked = await self._contacts_client.is_blocked(
                user_id=recipient_id,
                blocked_user_id=sender_user_id,
            )
            await self._own_redis.set(cache_key, "1" if blocked else "0", ex=60)
            return recipient_id, blocked

        results = await asyncio.gather(*[_check(rid) for rid in recipient_ids])
        return [rid for rid, blocked in results if not blocked]

    async def _get_members_cached(self, conversation_id: str) -> list:
        cache_key = f"notif:members:{conversation_id}"
        cached = await self._own_redis.get(cache_key)
        if cached:
            import json as _json

            data = _json.loads(cached)
            from app.infrastructure.messaging_client import MemberInfo

            return [MemberInfo(user_id=m["user_id"], role=m["role"]) for m in data]

        members = await self._messaging_client.get_conversation_members(conversation_id)
        if members:
            import json as _json

            await self._own_redis.set(
                cache_key,
                _json.dumps([{"user_id": m.user_id, "role": m.role} for m in members]),
                ex=30,
            )
        return members

    async def _get_user_cached(self, user_id: str):
        cache_key = f"notif:sender:{user_id}"
        cached = await self._own_redis.get(cache_key)
        if cached:
            import json as _json

            data = _json.loads(cached)
            from app.infrastructure.user_client import UserInfo

            return UserInfo(**data)

        users = await self._user_client.get_users_batch([user_id])
        if users:
            import json as _json

            user = users[0]
            await self._own_redis.set(
                cache_key,
                _json.dumps(
                    {
                        "user_id": user.user_id,
                        "display_name": user.display_name,
                        "username": user.username,
                        "avatar_media_id": user.avatar_media_id,
                    }
                ),
                ex=300,
            )
            return user
        return None

    async def _get_avatar_url_cached(self, media_id: str) -> str | None:
        if not media_id:
            return None
        cache_key = f"notif:avatar_url:{media_id}"
        cached = await self._own_redis.get(cache_key)
        if cached:
            return cached.decode() if isinstance(cached, bytes) else cached

        result = await self._item_storage_client.get_download_url(
            item_id=media_id,
            requester_user_id="system",
        )
        if result:
            await self._own_redis.set(cache_key, result.download_url, ex=600)
            return result.download_url
        return None
