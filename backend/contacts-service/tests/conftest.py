"""Shared pytest configuration for contacts-service tests.

Мокает отсутствующие в юнит-тестах артефакты: generated protobuf-модули и
`grpc.aio`, чтобы `UserServiceClient` мог импортироваться без собранного proto.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from unittest.mock import MagicMock

SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

# --- Fake generated proto modules -------------------------------------------
_user_pb2 = MagicMock()
_user_pb2_grpc = MagicMock()


# Настраиваем минимальные конструкторы запросов, чтобы импортёры не падали.
class _Request:
    def __init__(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self, k, v)


_user_pb2.GetUserByUserPublicKeyRequest = _Request
_user_pb2.UserExistsRequest = _Request
_user_pb2.GetUsersBatchRequest = _Request
_user_pb2.UserProfileResponse = MagicMock
_user_pb2.UserExistsResponse = MagicMock
_user_pb2.GetUsersBatchResponse = MagicMock

_generated_pkg = MagicMock()
_generated_pkg.user_pb2 = _user_pb2
_generated_pkg.user_pb2_grpc = _user_pb2_grpc

sys.modules.setdefault("app.generated.user_pb2", _user_pb2)
sys.modules.setdefault("app.generated.user_pb2_grpc", _user_pb2_grpc)

os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost:5432/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("USER_GRPC_HOST", "localhost")
os.environ.setdefault("USER_GRPC_PORT", "50052")
