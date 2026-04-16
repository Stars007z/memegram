# app/config.py
import os
from functools import lru_cache
from typing import Optional

from dotenv import load_dotenv

# Загружаем .env файл (если запускаем локально)
load_dotenv()


class Settings:
    """
    Конфигурация приложения.
    Значения берутся из переменных окружения или используются дефолты.
    """

    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://auth_user:auth_password@localhost:5432/auth_db"
    )

    JWT_SECRET: str = os.getenv("JWT_SECRET", "dev_secret_change_in_prod")
    JWT_ALGORITHM: str = os.getenv("JWT_ALGORITHM", "HS256")

    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50051))

    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379/0")

    CHALLENGE_TTL_SECONDS: int = int(os.getenv("CHALLENGE_TTL_SECONDS", 300))  # 5 минут

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")

    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    SERVICE_NAME: str = os.getenv("SERVICE_NAME", "auth-service")
    SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() == "production"

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """
    Возвращает кэшированный экземпляр настроек.
    Используется lru_cache для создания синглтона.
    """
    return Settings()


settings = get_settings()