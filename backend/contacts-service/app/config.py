import os
from functools import lru_cache
from dotenv import load_dotenv

load_dotenv()


class Settings:
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "postgresql+asyncpg://contacts_user:contacts_password@localhost:5432/contacts_db",
    )
    GRPC_PORT: int = int(os.getenv("GRPC_PORT", 50053))
    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    SERVICE_VERSION: str = os.getenv("SERVICE_VERSION", "1.0.0")
    SERVICE_NAME: str = os.getenv("SERVICE_NAME", "contacts-service")
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")

    USER_GRPC_HOST: str = os.getenv("USER_GRPC_HOST", "localhost")
    USER_GRPC_PORT: int = int(os.getenv("USER_GRPC_PORT", 50052))
    USER_GRPC_TIMEOUT: float = float(os.getenv("USER_GRPC_TIMEOUT", 5.0))

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT.lower() == "development"

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT.lower() == "production"

    @property
    def USER_GRPC_ADDRESS(self) -> str:
        return f"{self.USER_GRPC_HOST}:{self.USER_GRPC_PORT}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
