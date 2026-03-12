import redis.asyncio as redis
from app.config import settings
from typing import Optional


class RedisClient:
    _instance: Optional[redis.Redis] = None

    @classmethod
    async def get_instance(cls) -> redis.Redis:
        if cls._instance is None:
            cls._instance = redis.from_url(
                settings.REDIS_URL,
                encoding="utf-8",
                decode_responses=False
            )
        return cls._instance

    @classmethod
    async def close(cls):
        if cls._instance:
            await cls._instance.close()
            cls._instance = None


async def store_challenge(device_id: str, challenge: bytes, ttl: int = None) -> bool:
    """Сохранить challenge в Redis с TTL"""
    redis_client = await RedisClient.get_instance()
    key = f"auth:challenge:{device_id}"
    ttl = ttl or settings.CHALLENGE_TTL_SECONDS
    await redis_client.setex(key, ttl, challenge)
    return True


async def get_challenge(device_id: str) -> Optional[bytes]:
    """Получить challenge из Redis"""
    redis_client = await RedisClient.get_instance()
    key = f"auth:challenge:{device_id}"
    return await redis_client.get(key)


async def delete_challenge(device_id: str) -> bool:
    """Удалить challenge после использования"""
    redis_client = await RedisClient.get_instance()
    key = f"auth:challenge:{device_id}"
    result = await redis_client.delete(key)
    return result > 0


async def check_redis_health() -> bool:
    """Проверка подключения к Redis"""
    try:
        redis_client = await RedisClient.get_instance()
        await redis_client.ping()
        return True
    except Exception:
        return False