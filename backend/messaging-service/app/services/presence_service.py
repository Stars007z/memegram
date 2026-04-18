import uuid

import redis.asyncio as aioredis

from app.services.interfaces.presence_service import IPresenceService
from app.services.interfaces.stream_service import IStreamService


class PresenceServiceImpl(IPresenceService):

    def __init__(self, redis: aioredis.Redis, stream_service: IStreamService) -> None:
        self._redis = redis
        self._stream = stream_service

    async def set_typing(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
        conversation_id: uuid.UUID,
        is_typing: bool,
    ) -> bool:
        key = f"typing:{conversation_id}:{user_id}"

        if is_typing:
            await self._redis.setex(key, 5, b"1")
        else:
            await self._redis.delete(key)

        await self._stream.publish_event(
            conversation_id,
            {
                "event_type": "typing",
                "user_id": str(user_id),
                "is_typing": is_typing,
            },
        )
        return True

    async def set_online(
        self,
        user_id: uuid.UUID,
        device_id: uuid.UUID,
    ) -> bool:
        key = f"online:{user_id}:{device_id}"
        await self._redis.setex(key, 60, b"1")
        return True
