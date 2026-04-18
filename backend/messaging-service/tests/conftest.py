"""Shared pytest configuration for messaging-service tests.

Добавляет корень сервиса (и папку с сгенерированными grpc-модулями) в
sys.path и проставляет безопасные env-переменные по умолчанию, чтобы тесты
не зависели от локального окружения.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parent.parent
GENERATED_DIR = SERVICE_ROOT / "app" / "generated"

for p in (SERVICE_ROOT, GENERATED_DIR):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("JWT_SECRET", "test_secret")
os.environ.setdefault("JWT_ALGORITHM", "HS256")
os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://test:test@localhost:5432/test",
)
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("GRPC_PORT", "50054")
os.environ.setdefault("AUTH_GRPC_HOST", "localhost")
os.environ.setdefault("AUTH_GRPC_PORT", "50051")
os.environ.setdefault("CONTACTS_GRPC_HOST", "localhost")
os.environ.setdefault("CONTACTS_GRPC_PORT", "50053")
os.environ.setdefault("MEDIA_GRPC_HOST", "localhost")
os.environ.setdefault("MEDIA_GRPC_PORT", "50055")
