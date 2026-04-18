"""Shared pytest configuration for media-service tests.

Добавляет корень сервиса в sys.path и проставляет безопасные env-переменные
для Settings, чтобы тесты не зависели от наличия .env.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from unittest.mock import MagicMock

SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

# Мокаем тяжёлые опциональные зависимости (aioboto3/botocore) — тесты
# работают только с нашим S3Client, а импорт реальных клиентов не нужен.
for _mod in ("aioboto3", "botocore", "botocore.config", "botocore.exceptions"):
    sys.modules.setdefault(_mod, MagicMock())

os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost:5432/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("S3_BUCKET_NAME", "test-bucket")
os.environ.setdefault("S3_ENDPOINT_URL", "http://localhost:9000")
os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault("AWS_REGION", "eu-central-1")
