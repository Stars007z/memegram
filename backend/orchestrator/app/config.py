from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    APP_TITLE: str = "Memegram Orchestrator"
    APP_VERSION: str = "1.0.0"
    DEBUG: bool = False

    LOG_LEVEL: str = "INFO"
    SERVICE_NAME: str = "orchestrator"
    SERVICE_VERSION: str = "1.0.0"
    ENVIRONMENT: str = "development"

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() == "production"

    AUTH_GRPC_HOST: str = "localhost"
    AUTH_GRPC_PORT: int = 50051
    AUTH_GRPC_TIMEOUT: float = 10.0

    REDIS_URL: str = "redis://localhost:6379/0"

    USER_GRPC_HOST: str = "localhost"
    USER_GRPC_PORT: int = 50052
    USER_GRPC_TIMEOUT: float = 10.0

    CONTACTS_GRPC_HOST: str = "localhost"
    CONTACTS_GRPC_PORT: int = 50053
    CONTACTS_GRPC_TIMEOUT: float = 10.0

    MESSAGING_GRPC_HOST: str = "localhost"
    MESSAGING_GRPC_PORT: int = 50054
    MESSAGING_GRPC_TIMEOUT: float = 10.0

    MEDIA_GRPC_HOST: str = "localhost"
    MEDIA_GRPC_PORT: int = 50055
    MEDIA_GRPC_TIMEOUT: float = 10.0

    ITEM_STORAGE_GRPC_HOST: str = "localhost"
    ITEM_STORAGE_GRPC_PORT: int = 50056
    ITEM_STORAGE_GRPC_TIMEOUT: float = 10.0

    NOTIFICATIONS_GRPC_HOST: str = "localhost"
    NOTIFICATIONS_GRPC_PORT: int = 50057
    NOTIFICATIONS_GRPC_TIMEOUT: float = 10.0

    AUTO_DELETE_CRON_HOUR: int = 3
    AUTO_DELETE_CRON_MINUTE: int = 0

    @property
    def AUTH_GRPC_ADDRESS(self) -> str:
        return f"{self.AUTH_GRPC_HOST}:{self.AUTH_GRPC_PORT}"

    @property
    def USER_GRPC_ADDRESS(self) -> str:
        return f"{self.USER_GRPC_HOST}:{self.USER_GRPC_PORT}"

    @property
    def CONTACTS_GRPC_ADDRESS(self) -> str:
        return f"{self.CONTACTS_GRPC_HOST}:{self.CONTACTS_GRPC_PORT}"

    @property
    def MESSAGING_GRPC_ADDRESS(self) -> str:
        return f"{self.MESSAGING_GRPC_HOST}:{self.MESSAGING_GRPC_PORT}"

    @property
    def MEDIA_GRPC_ADDRESS(self) -> str:
        return f"{self.MEDIA_GRPC_HOST}:{self.MEDIA_GRPC_PORT}"

    @property
    def ITEM_STORAGE_GRPC_ADDRESS(self) -> str:
        return f"{self.ITEM_STORAGE_GRPC_HOST}:{self.ITEM_STORAGE_GRPC_PORT}"

    @property
    def NOTIFICATIONS_GRPC_ADDRESS(self) -> str:
        return f"{self.NOTIFICATIONS_GRPC_HOST}:{self.NOTIFICATIONS_GRPC_PORT}"


settings = Settings()
