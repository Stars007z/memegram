import asyncio
import json
import uuid
from typing import Any, AsyncIterator

import redis.asyncio as aioredis

from app.services.interfaces.stream_service import IStreamService


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
