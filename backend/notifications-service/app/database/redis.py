from typing import Optional

import redis.asyncio as aioredis

from app.config import settings


class RedisClient:
    """Own Redis instance (cache, deduplication) — port 6382."""

    _instance: Optional[aioredis.Redis] = None

    @classmethod
    async def get_instance(cls) -> aioredis.Redis:
        if cls._instance is None:
            cls._instance = aioredis.from_url(
                settings.REDIS_URL,
                encoding="utf-8",
                decode_responses=False,
            )
        return cls._instance

    @classmethod
    async def close(cls) -> None:
        if cls._instance:
            await cls._instance.close()
            cls._instance = None


class MessagingRedisClient:
    """Messaging-service Redis instance — port 6381.

    Used exclusively for consuming the ``notifications:events`` stream.
    """

    _instance: Optional[aioredis.Redis] = None

    @classmethod
    async def get_instance(cls) -> aioredis.Redis:
        if cls._instance is None:
            cls._instance = aioredis.from_url(
                settings.MESSAGING_REDIS_URL,
                encoding="utf-8",
                decode_responses=False,
            )
        return cls._instance

    @classmethod
    async def close(cls) -> None:
        if cls._instance:
            await cls._instance.close()
            cls._instance = None


async def check_redis_health() -> bool:
    try:
        client = await RedisClient.get_instance()
        await client.ping()
        return True
    except Exception:
        return False
