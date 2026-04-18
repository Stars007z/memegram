import redis.asyncio as redis
from typing import Optional
from app.config import settings

LAST_ACTIVE_DEBOUNCE_TTL = 60

class RedisClient:
    instance: Optional[redis.Redis] = None

    @classmethod
    async def get_instance(cls) -> redis.Redis:
        if cls.instance is None:
            cls.instance = redis.from_url(
                settings.REDIS_URL,
                encoding="utf-8",
                decode_responses=True,
            )
        return cls.instance

    @classmethod
    async def close(cls):
        if cls.instance:
            await cls.instance.close()
            cls.instance = None

async def check_redis_health() -> bool:
    try:
        client = await RedisClient.get_instance()
        await client.ping()
        return True
    except Exception:
        return False

async def get_last_active_debounce_key(user_id: str) -> str:
    return f"last_active_debounce:{user_id}"

async def check_and_set_last_active_debounce(user_id: str) -> bool:
    """
    Возвращает True если нужно обновить БД (ключа не было).
    Возвращает False если ключ уже есть — пропустить запись.
    """
    client = await RedisClient.get_instance()
    key = await get_last_active_debounce_key(user_id)
    result = await client.set(key, "1", ex=LAST_ACTIVE_DEBOUNCE_TTL, nx=True)
    return result is not None
