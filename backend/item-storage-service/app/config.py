import os
from functools import lru_cache
from dotenv import load_dotenv

load_dotenv()


class Settings:
    DATABASE_URL: str = os.getenv("DATABASE_URL", "postgresql+asyncpg://storage_user:storage_password@localhost:5432/item_storage_db")
    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50056))
    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")
    SERVICE_NAME: str = os.getenv("SERVICE_NAME", "item-storage-service")
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")

    AWS_ACCESS_KEY_ID: str = os.getenv("AWS_ACCESS_KEY_ID", "")
    AWS_SECRET_ACCESS_KEY: str = os.getenv("AWS_SECRET_ACCESS_KEY", "")
    AWS_REGION: str = os.getenv("AWS_REGION", "eu-central-1")
    S3_BUCKET_NAME: str = os.getenv("S3_BUCKET_NAME", "messenger-items-dev")
    S3_ENDPOINT_URL: str = os.getenv("S3_ENDPOINT_URL", "")
    S3_SSE_TYPE: str = os.getenv("S3_SSE_TYPE", "AES256")
    KMS_KEY_ID: str = os.getenv("KMS_KEY_ID", "")

    PRESIGNED_UPLOAD_TTL: int = int(os.getenv("PRESIGNED_UPLOAD_TTL", 3600))
    PRESIGNED_DOWNLOAD_TTL: int = int(os.getenv("PRESIGNED_DOWNLOAD_TTL", 900))
    PENDING_CLEANUP_AFTER_SECONDS: int = int(os.getenv("PENDING_CLEANUP_AFTER_SECONDS", 7200))

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() in ("production", "staging")

    @property
    def s3_endpoint(self) -> str | None:
        return self.S3_ENDPOINT_URL or None


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
