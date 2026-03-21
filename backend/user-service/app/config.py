import os
from functools import lru_cache
from dotenv import load_dotenv

load_dotenv()


class Settings:
    DATABASE_URL: str = os.getenv("DATABASE_URL", "postgresql+asyncpg://useruser:userpassword@localhost:5432/userdb")
    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50052))
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379/1")
    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
