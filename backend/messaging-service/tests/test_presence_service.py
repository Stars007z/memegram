"""Unit tests for `app.services.presence_service.PresenceServiceImpl`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают Redis и
IStreamService, чтобы проверить именно бизнес-логику presence.
"""

from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services.interfaces.stream_service import IStreamService
from app.services.presence_service import PresenceServiceImpl


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def redis_mock() -> MagicMock:
    redis = MagicMock()
    redis.setex = AsyncMock()
    redis.delete = AsyncMock()
    return redis


@pytest.fixture
def stream_mock() -> MagicMock:
    stream = MagicMock(spec=IStreamService)
    stream.publish_event = AsyncMock()
    return stream


@pytest.fixture
def service(redis_mock, stream_mock) -> PresenceServiceImpl:
    return PresenceServiceImpl(redis_mock, stream_mock)


# ---------------------------------------------------------------------------
# set_typing
# ---------------------------------------------------------------------------
class TestSetTyping:
    async def test_typing_true_sets_redis_key_and_publishes(
        self,
        service,
        redis_mock,
        stream_mock,
    ):
        # Arrange
        user_id = uuid.uuid4()
        device_id = uuid.uuid4()
        conv_id = uuid.uuid4()

        # Act
        ok = await service.set_typing(user_id, device_id, conv_id, True)

        # Assert
        assert ok is True
        redis_mock.setex.assert_awaited_once_with(
            f"typing:{conv_id}:{user_id}",
            5,
            b"1",
        )
        redis_mock.delete.assert_not_awaited()
        stream_mock.publish_event.assert_awaited_once()
        args = stream_mock.publish_event.await_args.args
        assert args[0] == conv_id
        assert args[1]["event_type"] == "typing"
        assert args[1]["is_typing"] is True
        assert args[1]["user_id"] == str(user_id)

    async def test_typing_false_deletes_redis_key_and_publishes(
        self,
        service,
        redis_mock,
        stream_mock,
    ):
        # Arrange
        user_id = uuid.uuid4()
        device_id = uuid.uuid4()
        conv_id = uuid.uuid4()

        # Act
        ok = await service.set_typing(user_id, device_id, conv_id, False)

        # Assert
        assert ok is True
        redis_mock.setex.assert_not_awaited()
        redis_mock.delete.assert_awaited_once_with(
            f"typing:{conv_id}:{user_id}",
        )
        stream_mock.publish_event.assert_awaited_once()
        assert stream_mock.publish_event.await_args.args[1]["is_typing"] is False


# ---------------------------------------------------------------------------
# set_online
# ---------------------------------------------------------------------------
class TestSetOnline:
    async def test_online_sets_key_with_ttl(self, service, redis_mock):
        # Arrange
        user_id = uuid.uuid4()
        device_id = uuid.uuid4()

        # Act
        ok = await service.set_online(user_id, device_id)

        # Assert
        assert ok is True
        redis_mock.setex.assert_awaited_once_with(
            f"online:{user_id}:{device_id}",
            60,
            b"1",
        )
