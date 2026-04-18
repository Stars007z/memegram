import os
from functools import lru_cache

from dotenv import load_dotenv

load_dotenv()


class Settings:
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://notif_user:notif_password@localhost:5438/notifications_db",
    )
    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50057))

    # Own Redis (cache, deduplication)
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6382/0")

    # Messaging-service Redis (consume notifications:events stream)
    MESSAGING_REDIS_URL: str = os.getenv(
        "MESSAGING_REDIS_URL", "redis://localhost:6381/0",
    )
    STREAM_NAME: str = os.getenv("STREAM_NAME", "notifications:events")
    CONSUMER_GROUP: str = os.getenv("CONSUMER_GROUP", "notifications-cg")

    # FCM (Android)
    GOOGLE_APPLICATION_CREDENTIALS: str = os.getenv(
        "GOOGLE_APPLICATION_CREDENTIALS", "",
    )
    FCM_PROJECT_ID: str = os.getenv("FCM_PROJECT_ID", "")

    # APNs (iOS)
    APNS_KEY_PATH: str = os.getenv("APNS_KEY_PATH", "")
    APNS_KEY_ID: str = os.getenv("APNS_KEY_ID", "")
    APNS_TEAM_ID: str = os.getenv("APNS_TEAM_ID", "")
    APNS_BUNDLE_ID: str = os.getenv("APNS_BUNDLE_ID", "com.memegram.app")
    APNS_USE_SANDBOX: bool = os.getenv("APNS_USE_SANDBOX", "true").lower() == "true"

    # Retry
    MAX_RETRY_ATTEMPTS: int = int(os.getenv("MAX_RETRY_ATTEMPTS", 5))
    RETRY_BASE_DELAY_SEC: float = float(os.getenv("RETRY_BASE_DELAY_SEC", 1))
    RETRY_JITTER_PERCENT: int = int(os.getenv("RETRY_JITTER_PERCENT", 30))
    MAX_TOKEN_CONSECUTIVE_FAILURES: int = int(
        os.getenv("MAX_TOKEN_CONSECUTIVE_FAILURES", 3),
    )
    STREAM_DEAD_LETTER_THRESHOLD: int = int(
        os.getenv("STREAM_DEAD_LETTER_THRESHOLD", 10),
    )

    # gRPC dependencies
    MESSAGING_GRPC_HOST: str = os.getenv("MESSAGING_GRPC_HOST", "localhost")
    MESSAGING_GRPC_PORT: int = int(os.getenv("MESSAGING_GRPC_PORT", 50054))

    USER_GRPC_HOST: str = os.getenv("USER_GRPC_HOST", "localhost")
    USER_GRPC_PORT: int = int(os.getenv("USER_GRPC_PORT", 50052))

    ITEM_STORAGE_GRPC_HOST: str = os.getenv("ITEM_STORAGE_GRPC_HOST", "localhost")
    ITEM_STORAGE_GRPC_PORT: int = int(os.getenv("ITEM_STORAGE_GRPC_PORT", 50056))

    CONTACTS_GRPC_HOST: str = os.getenv("CONTACTS_GRPC_HOST", "localhost")
    CONTACTS_GRPC_PORT: int = int(os.getenv("CONTACTS_GRPC_PORT", 50053))

    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    SERVICE_NAME: str = os.getenv("SERVICE_NAME", "notifications-service")

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() in ("production", "staging")

    @property
    def messaging_grpc_address(self) -> str:
        return f"{self.MESSAGING_GRPC_HOST}:{self.MESSAGING_GRPC_PORT}"

    @property
    def user_grpc_address(self) -> str:
        return f"{self.USER_GRPC_HOST}:{self.USER_GRPC_PORT}"

    @property
    def item_storage_grpc_address(self) -> str:
        return f"{self.ITEM_STORAGE_GRPC_HOST}:{self.ITEM_STORAGE_GRPC_PORT}"

    @property
    def contacts_grpc_address(self) -> str:
        return f"{self.CONTACTS_GRPC_HOST}:{self.CONTACTS_GRPC_PORT}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
