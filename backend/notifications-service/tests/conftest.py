"""Shared pytest configuration for notifications-service tests.

Мокает отсутствующие в окружении тяжёлые зависимости (`firebase_admin`,
`aioapns`, `asyncpg`) и создание асинхронного движка через
`app.database.session`, чтобы `EventConsumer` и пушеры могли импортироваться
без реальной инфраструктуры.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from unittest.mock import MagicMock

SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

# --- Fake heavy / optional modules ------------------------------------------
_grpc_stub = MagicMock()
_grpc_stub.aio = MagicMock()
_grpc_stub.RpcError = type("RpcError", (Exception,), {})
sys.modules.setdefault("grpc", _grpc_stub)
sys.modules.setdefault("grpc.aio", _grpc_stub.aio)

for _mod in (
    "firebase_admin",
    "firebase_admin.credentials",
    "firebase_admin.messaging",
    "aioapns",
    "asyncpg",
    "app.generated",
    "app.generated.messaging_pb2",
    "app.generated.messaging_pb2_grpc",
    "app.generated.user_pb2",
    "app.generated.user_pb2_grpc",
    "app.generated.item_storage_pb2",
    "app.generated.item_storage_pb2_grpc",
    "app.generated.contacts_pb2",
    "app.generated.contacts_pb2_grpc",
):
    sys.modules.setdefault(_mod, MagicMock())

os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost:5432/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("MESSAGING_REDIS_URL", "redis://localhost:6379/1")
os.environ.setdefault("STREAM_NAME", "notifications:events")
os.environ.setdefault("CONSUMER_GROUP", "notifications-cg")
os.environ.setdefault("AUTH_GRPC_ADDRESS", "localhost:50051")
os.environ.setdefault("USER_GRPC_ADDRESS", "localhost:50052")

# --- Stub out app.database.session to avoid touching sqlalchemy engine ------
_session_stub = MagicMock()
_session_stub.get_session = MagicMock()
sys.modules.setdefault("app.database.session", _session_stub)
