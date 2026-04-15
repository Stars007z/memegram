"""Redis Streams event consumer for notifications.

Reads events from ``notifications:events`` stream published by messaging-service,
enriches them with metadata, and dispatches push notifications.
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any

import redis.asyncio as aioredis

from app.config import settings
from app.database.session import get_session
from app.infrastructure.item_storage_client import IItemStorageClient
from app.infrastructure.messaging_client import IMessagingClient
from app.infrastructure.user_client import IUserClient
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

logger = logging.getLogger(__name__)

# message_type → human-readable label
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
    ) -> None:
        self._messaging_redis = messaging_redis
        self._own_redis = own_redis
        self._messaging_client = messaging_client
        self._user_client = user_client
        self._item_storage_client = item_storage_client

        self._fcm_sender: IPushSender = FcmSender()
        self._apns_sender: IPushSender = ApnsSender()

        self._stream = settings.STREAM_NAME
        self._group = settings.CONSUMER_GROUP
        self._consumer = f"notifications-{uuid.uuid4().hex[:8]}"
        self._running = False

    async def start(self) -> None:
        """Create consumer group (if needed) and start consuming."""
        try:
            await self._messaging_redis.xgroup_create(
                self._stream, self._group, id="0", mkstream=True,
            )
            logger.info("Created consumer group %s on %s", self._group, self._stream)
        except Exception:
            # Group already exists
            pass

        self._running = True
        logger.info("Event consumer started: consumer=%s", self._consumer)
        await asyncio.gather(
            self._consume_loop(),
            self._claim_loop(),
        )

    async def stop(self) -> None:
        self._running = False

    # ── Main consume loop ────────────────────────────────────────────

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
                logger.error("Consumer loop error: %s", e)
                await asyncio.sleep(1)

    # ── Claim stale messages ─────────────────────────────────────────

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
                # result = (next_start_id, [(id, fields), ...], [deleted_ids])
                if result and len(result) > 1:
                    claimed = result[1]
                    for entry_id, fields in claimed:
                        await self._process_entry(entry_id, fields)
            except Exception as e:
                logger.error("Claim loop error: %s", e)

    # ── Entry processing ─────────────────────────────────────────────

    async def _process_entry(self, entry_id: bytes | str, fields: dict) -> None:
        entry_id_str = entry_id.decode() if isinstance(entry_id, bytes) else entry_id
        try:
            # Decode fields
            decoded: dict[str, str] = {}
            for k, v in fields.items():
                key = k.decode() if isinstance(k, bytes) else k
                val = v.decode() if isinstance(v, bytes) else v
                decoded[key] = val

            event_type = decoded.get("type", "")
            payload_raw = decoded.get("payload", "{}")
            payload = json.loads(payload_raw)

            # Deduplication
            dedup_key = f"notif:dedup:{entry_id_str}"
            was_set = await self._own_redis.set(dedup_key, "1", nx=True, ex=3600)
            if not was_set:
                logger.debug("Duplicate event %s, skipping", entry_id_str)
                await self._messaging_redis.xack(self._stream, self._group, entry_id)
                return

            if event_type == "new_message":
                await self._handle_new_message(payload)
            elif event_type == "member_added":
                await self._handle_member_added(payload)
            elif event_type == "member_kicked":
                await self._handle_member_kicked(payload)
            else:
                logger.warning("Unknown event type: %s", event_type)

            await self._messaging_redis.xack(self._stream, self._group, entry_id)

        except Exception as e:
            logger.error("Failed to process entry %s: %s", entry_id_str, e)

    # ── new_message ──────────────────────────────────────────────────

    async def _handle_new_message(self, event: dict) -> None:
        conversation_id = event.get("conversation_id", "")
        conversation_type = event.get("conversation_type", "")
        conversation_name = event.get("conversation_name", "")
        sender_user_id = event.get("sender_user_id", "")
        message_type = event.get("message_type", "text")
        avatar_media_id = event.get("avatar_media_id", "")
        created_at = event.get("created_at", "")

        # 1. Get conversation members
        members = await self._get_members_cached(conversation_id)
        recipient_ids = [m.user_id for m in members if m.user_id != sender_user_id]
        if not recipient_ids:
            return

        # 2. Get sender info
        sender_info = await self._get_user_cached(sender_user_id)
        sender_name = sender_info.display_name if sender_info else "Unknown"
        sender_avatar_media_id = sender_info.avatar_media_id if sender_info else ""

        # 3. Determine avatar
        effective_avatar_media_id = avatar_media_id if conversation_type == "group" else sender_avatar_media_id
        avatar_url = await self._get_avatar_url_cached(effective_avatar_media_id) if effective_avatar_media_id else None

        # 4. Build title/body
        type_label = MESSAGE_TYPE_LABELS.get(message_type, "Новое сообщение")
        if conversation_type == "group":
            title = conversation_name or "Группа"
            body = f"{sender_name}: {type_label}"
        else:
            title = sender_name
            body = type_label

        # 5. Get tokens and send
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

    # ── member_added ─────────────────────────────────────────────────

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

    # ── member_kicked ────────────────────────────────────────────────

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

    # ── Send push to multiple users ──────────────────────────────────

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
                logger.debug("No active tokens for users %s", recipient_user_ids)
                return

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
            await repo.mark_success(token_id)
        elif result.error_type == PushErrorType.PERMANENT_TOKEN:
            logger.info("Permanent token error for %s: %s — deactivating", token_id, result.error_code)
            await repo.deactivate_token(token_id)
        else:
            logger.warning("Push failed for token %s: %s", token_id, result.error_code)
            await repo.increment_failure(
                token_id,
                max_failures=settings.MAX_TOKEN_CONSECUTIVE_FAILURES,
            )

    # ── Caching helpers ──────────────────────────────────────────────

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
                _json.dumps({
                    "user_id": user.user_id,
                    "display_name": user.display_name,
                    "username": user.username,
                    "avatar_media_id": user.avatar_media_id,
                }),
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
