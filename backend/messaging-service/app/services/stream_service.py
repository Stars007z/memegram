import asyncio
import json
import uuid
from typing import Any, AsyncIterator

import redis.asyncio as aioredis

from app.logging_config import get_logger
from app.services.interfaces.stream_service import IStreamService

logger = get_logger(__name__)

# Event types that should also be persisted to the notifications stream so the
# notifications-service can deliver pushes to offline / background clients.
# NOTE: keep this in sync with the dispatch table in notifications-service
# (event_consumer.EventConsumer._process_entry). Adding an event here without
# a handler there will only generate `event_consumer.unknown_event_type` log
# noise and consume a stream slot.
_NOTIFICATION_EVENT_TYPES: frozenset[str] = frozenset(
    {
        "new_message",
        "member_added",
        "member_kicked",
        "conversation_deleted",
    }
)
_NOTIFICATIONS_STREAM = "notifications:events"
_NOTIFICATIONS_STREAM_MAXLEN = 100_000


class StreamServiceImpl(IStreamService):
    """Redis Pub/Sub based event streaming."""

    def __init__(self, redis: aioredis.Redis) -> None:
        self._redis = redis

    async def subscribe(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_ids: list[uuid.UUID],
    ) -> AsyncIterator[dict[str, Any]]:
        pubsub = self._redis.pubsub()
        channels = [f"conv:{cid}" for cid in conversation_ids]

        await pubsub.subscribe(*channels)
        try:
            while True:
                message = await pubsub.get_message(
                    ignore_subscribe_messages=True,
                    timeout=1.0,
                )
                if message and message["type"] == "message":
                    data = message["data"]
                    if isinstance(data, bytes):
                        data = data.decode("utf-8")
                    yield json.loads(data)
                else:
                    await asyncio.sleep(0.1)
        finally:
            await pubsub.unsubscribe(*channels)
            await pubsub.aclose()

    async def publish_event(
        self,
        conversation_id: uuid.UUID,
        event: dict[str, Any],
    ) -> None:
        channel = f"conv:{conversation_id}"
        event["conversation_id"] = str(conversation_id)
        payload = json.dumps(event)
        await self._redis.publish(channel, payload)

        event_type = event.get("event_type", "")
        if event_type in _NOTIFICATION_EVENT_TYPES:
            try:
                await self._redis.xadd(
                    _NOTIFICATIONS_STREAM,
                    {"type": event_type, "payload": payload},
                    maxlen=_NOTIFICATIONS_STREAM_MAXLEN,
                    approximate=True,
                )
            except Exception as exc:  # pragma: no cover - best-effort fan-out
                logger.warning(
                    "stream.notifications_xadd_failed",
                    event_type=event_type,
                    conversation_id=str(conversation_id),
                    error=str(exc),
                )
