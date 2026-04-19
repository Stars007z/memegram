"""Unit tests for `app.services.stream_service.StreamServiceImpl`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают redis pub/sub,
чтобы проверить именно логику публикации/подписки событий.
"""

from __future__ import annotations

import json
import uuid
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services.stream_service import StreamServiceImpl


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_redis() -> MagicMock:
    """Собирает MagicMock Redis с async-методами."""
    redis = MagicMock()
    redis.publish = AsyncMock()
    return redis


@pytest.fixture
def redis_mock() -> MagicMock:
    return _make_redis()


@pytest.fixture
def service(redis_mock) -> StreamServiceImpl:
    return StreamServiceImpl(redis_mock)


# ---------------------------------------------------------------------------
# publish_event
# ---------------------------------------------------------------------------
class TestPublishEvent:
    async def test_publishes_json_payload_to_conversation_channel(
        self,
        service,
        redis_mock,
    ):
        # Arrange
        conv_id = uuid.uuid4()
        event = {"event_type": "new_message", "foo": "bar"}

        # Act
        await service.publish_event(conv_id, event)

        # Assert
        redis_mock.publish.assert_awaited_once()
        channel, payload = redis_mock.publish.await_args.args
        assert channel == f"conv:{conv_id}"
        decoded = json.loads(payload)
        assert decoded["event_type"] == "new_message"
        assert decoded["foo"] == "bar"
        assert decoded["conversation_id"] == str(conv_id)

    async def test_mutates_event_with_conversation_id(self, service, redis_mock):
        # Arrange
        conv_id = uuid.uuid4()
        event: dict = {}

        # Act
        await service.publish_event(conv_id, event)

        # Assert
        assert event["conversation_id"] == str(conv_id)


# ---------------------------------------------------------------------------
# subscribe (smoke test)
# ---------------------------------------------------------------------------
class TestSubscribe:
    async def test_subscribe_yields_decoded_message_and_cleans_up(
        self,
        service,
        redis_mock,
    ):
        # Arrange
        conv_id = uuid.uuid4()
        pubsub = MagicMock()
        pubsub.subscribe = AsyncMock()
        pubsub.unsubscribe = AsyncMock()
        pubsub.aclose = AsyncMock()
        payload = {"event_type": "new_message", "x": 1}
        pubsub.get_message = AsyncMock(
            return_value={"type": "message", "data": json.dumps(payload).encode()},
        )
        redis_mock.pubsub = MagicMock(return_value=pubsub)

        # Act
        gen = service.subscribe(uuid.uuid4(), uuid.uuid4(), [conv_id])
        first = await gen.__anext__()
        await gen.aclose()

        # Assert
        assert first == payload
        pubsub.subscribe.assert_awaited_once_with(f"conv:{conv_id}")
        pubsub.unsubscribe.assert_awaited_once_with(f"conv:{conv_id}")
        pubsub.aclose.assert_awaited_once()

    async def test_subscribe_handles_string_data(self, service, redis_mock):
        # Arrange
        conv_id = uuid.uuid4()
        pubsub = MagicMock()
        pubsub.subscribe = AsyncMock()
        pubsub.unsubscribe = AsyncMock()
        pubsub.aclose = AsyncMock()
        payload = {"event_type": "typing"}
        pubsub.get_message = AsyncMock(
            return_value={"type": "message", "data": json.dumps(payload)},
        )
        redis_mock.pubsub = MagicMock(return_value=pubsub)

        # Act
        gen = service.subscribe(uuid.uuid4(), uuid.uuid4(), [conv_id])
        first = await gen.__anext__()
        await gen.aclose()

        # Assert
        assert first == payload
