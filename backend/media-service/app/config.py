import os
from functools import lru_cache

from dotenv import load_dotenv

load_dotenv()


class Settings:
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://media_user:media_password@localhost:5432/media_db",
    )
    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50055))

    AWS_ACCESS_KEY_ID: str = os.getenv("AWS_ACCESS_KEY_ID", "")
    AWS_SECRET_ACCESS_KEY: str = os.getenv("AWS_SECRET_ACCESS_KEY", "")
    AWS_REGION: str = os.getenv("AWS_REGION", "eu-central-1")
    S3_BUCKET_NAME: str = os.getenv("S3_BUCKET_NAME", "messenger-media-dev")
    S3_ENDPOINT_URL: str | None = os.getenv("S3_ENDPOINT_URL") or None
    # Public endpoint used in presigned URLs (e.g. http://10.0.2.2:9000 for Android emulator).
    # If unset, S3_ENDPOINT_URL is used (suitable for production AWS S3).
    S3_PUBLIC_ENDPOINT: str | None = os.getenv("S3_PUBLIC_ENDPOINT") or None

    PRESIGNED_UPLOAD_TTL: int = int(os.getenv("PRESIGNED_UPLOAD_TTL", 3600))
    PRESIGNED_DOWNLOAD_TTL: int = int(os.getenv("PRESIGNED_DOWNLOAD_TTL", 900))
    MAX_UPLOAD_SIZE_BYTES: int = int(os.getenv("MAX_UPLOAD_SIZE_BYTES", 104_857_600))

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")

    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    SERVICE_NAME: str = os.getenv("SERVICE_NAME", "media-service")

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() == "production"

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
