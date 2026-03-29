import os
from functools import lru_cache

from dotenv import load_dotenv

load_dotenv()


class Settings:
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://messaging_user:messaging_password@localhost:5432/messaging_db",
    )
    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50054))
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379/0")

    AUTH_GRPC_HOST: str = os.getenv("AUTH_GRPC_HOST", "localhost")
    AUTH_GRPC_PORT: int = int(os.getenv("AUTH_GRPC_PORT", 50051))

    CONTACTS_GRPC_HOST: str = os.getenv("CONTACTS_GRPC_HOST", "localhost")
    CONTACTS_GRPC_PORT: int = int(os.getenv("CONTACTS_GRPC_PORT", 50053))

    MEDIA_GRPC_HOST: str = os.getenv("MEDIA_GRPC_HOST", "localhost")
    MEDIA_GRPC_PORT: int = int(os.getenv("MEDIA_GRPC_PORT", 50055))

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")

    MAX_UPLOAD_SIZE_BYTES: int = 104_857_600  # 100 MB
    PRESIGNED_UPLOAD_TTL: int = 3600
    PRESIGNED_DOWNLOAD_TTL: int = 900

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"

    @property
    def auth_grpc_address(self) -> str:
        return f"{self.AUTH_GRPC_HOST}:{self.AUTH_GRPC_PORT}"

    @property
    def contacts_grpc_address(self) -> str:
        return f"{self.CONTACTS_GRPC_HOST}:{self.CONTACTS_GRPC_PORT}"

    @property
    def media_grpc_address(self) -> str:
        return f"{self.MEDIA_GRPC_HOST}:{self.MEDIA_GRPC_PORT}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
