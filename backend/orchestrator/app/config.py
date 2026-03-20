from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    APP_TITLE: str = "Memegram Orchestrator"
    APP_VERSION: str = "1.0.0"
    DEBUG: bool = False

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

    # Cron schedule for CheckAndProcessAutoDelete (UTC)
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


settings = Settings()
