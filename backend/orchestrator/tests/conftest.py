"""Shared pytest configuration for orchestrator tests.

Добавляет корень сервиса в sys.path и проставляет безопасные env-переменные
для Settings, чтобы тесты не зависели от наличия .env.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost:5432/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AUTH_GRPC_HOST", "localhost")
os.environ.setdefault("AUTH_GRPC_PORT", "50051")
os.environ.setdefault("USER_GRPC_HOST", "localhost")
os.environ.setdefault("USER_GRPC_PORT", "50052")
