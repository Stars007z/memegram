"""Unit tests for `app.services.push_sender`.

Покрывает `FcmSender`, `ApnsSender` и `send_with_retry`. Внешние SDK
(`firebase_admin`, `aioapns`) замоканы через `sys.modules` в `conftest.py`,
поэтому все проверки — чисто логика классификации ошибок и ретраев.
"""

from __future__ import annotations

import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.push_sender import (
    ApnsSender,
    FcmSender,
    PushErrorType,
    PushPayload,
    PushPlatform,
    PushResult,
    send_with_retry,
)


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_payload(platform: PushPlatform = PushPlatform.ANDROID) -> PushPayload:
    return PushPayload(
        token="device-token-123",
        platform=platform,
        title="Hello",
        body="World",
        data={"event_type": "new_message", "conversation_id": "c-1"},
        thread_id="c-1",
        avatar_url="https://cdn/test.png",
    )


@pytest.fixture
def fcm_admin():
    """Свежий мок `firebase_admin` + `firebase_admin.messaging` на каждый тест.

    `push_sender` делает `from firebase_admin import messaging as fcm_messaging`
    и `from firebase_admin import credentials` внутри функций, поэтому важно
    подменить именно атрибуты модуля, уже лежащего в `sys.modules`.
    """
    admin = MagicMock()
    admin.initialize_app = MagicMock()
    creds = MagicMock()
    creds.Certificate = MagicMock(return_value=MagicMock())
    messaging = MagicMock()
    messaging.Message = MagicMock(side_effect=lambda **kw: SimpleNamespace(**kw))
    messaging.AndroidConfig = MagicMock(side_effect=lambda **kw: SimpleNamespace(**kw))
    messaging.send = MagicMock(return_value="message-id")
    admin.credentials = creds
    admin.messaging = messaging
    with patch.dict(
        sys.modules,
        {
            "firebase_admin": admin,
            "firebase_admin.credentials": creds,
            "firebase_admin.messaging": messaging,
        },
    ):
        yield SimpleNamespace(admin=admin, messaging=messaging)


@pytest.fixture
def fcm_messaging(fcm_admin):
    """Удобный shortcut к подменённому `firebase_admin.messaging`."""
    return fcm_admin.messaging


# ---------------------------------------------------------------------------
# FcmSender.send
# ---------------------------------------------------------------------------
class TestFcmSenderSend:
    async def test_send_success_returns_success_result(
        self,
        fcm_admin,
        fcm_messaging,
    ):
        # Arrange
        sender = FcmSender()
        payload = _make_payload()

        # Act
        result = await sender.send(payload)

        # Assert
        assert result.success is True
        assert result.error_type is None
        fcm_messaging.send.assert_called_once()

    async def test_send_returns_transient_when_initialization_fails(self):
        # Arrange
        broken = MagicMock()
        broken.initialize_app = MagicMock(side_effect=RuntimeError("boom"))
        sender = FcmSender()

        # Act
        with patch.dict(sys.modules, {"firebase_admin": broken}):
            result = await sender.send(_make_payload())

        # Assert
        assert result.success is False
        assert result.error_type is PushErrorType.TRANSIENT
        assert result.error_code == "fcm_not_initialized"

    async def test_send_unregistered_error_is_permanent_token(
        self,
        fcm_admin,
        fcm_messaging,
    ):
        # Arrange
        fcm_messaging.send.side_effect = RuntimeError(
            "Requested entity was not found: UNREGISTERED token",
        )
        sender = FcmSender()

        # Act
        result = await sender.send(_make_payload())

        # Assert
        assert result.success is False
        assert result.error_type is PushErrorType.PERMANENT_TOKEN
        assert result.error_code == "UNREGISTERED"

    async def test_send_invalid_argument_error_is_permanent_token(
        self,
        fcm_admin,
        fcm_messaging,
    ):
        # Arrange
        fcm_messaging.send.side_effect = ValueError("Invalid argument: bad token")
        sender = FcmSender()

        # Act
        result = await sender.send(_make_payload())

        # Assert
        assert result.error_type is PushErrorType.PERMANENT_TOKEN
        assert result.error_code == "INVALID_ARGUMENT"

    async def test_send_quota_error_is_rate_limit(self, fcm_admin, fcm_messaging):
        # Arrange
        fcm_messaging.send.side_effect = RuntimeError("HTTP 429 quota exceeded")
        sender = FcmSender()

        # Act
        result = await sender.send(_make_payload())

        # Assert
        assert result.error_type is PushErrorType.RATE_LIMIT
        assert result.retry_after == 60

    async def test_send_unknown_error_is_transient(self, fcm_admin, fcm_messaging):
        # Arrange
        fcm_messaging.send.side_effect = RuntimeError("something exploded")
        sender = FcmSender()

        # Act
        result = await sender.send(_make_payload())

        # Assert
        assert result.error_type is PushErrorType.TRANSIENT
        assert result.error_code.startswith("something")


# ---------------------------------------------------------------------------
# ApnsSender.send
# ---------------------------------------------------------------------------
class TestApnsSenderSend:
    async def test_send_success_returns_success_result(self):
        # Arrange
        sender = ApnsSender()
        client = MagicMock()
        client.send_notification = AsyncMock(
            return_value=SimpleNamespace(is_successful=True, description=None),
        )
        sender._client = client
        fake_aioapns = MagicMock()
        fake_aioapns.NotificationRequest = MagicMock(
            side_effect=lambda **kw: SimpleNamespace(**kw),
        )

        # Act
        with patch.dict(sys.modules, {"aioapns": fake_aioapns}):
            result = await sender.send(_make_payload(PushPlatform.IOS))

        # Assert
        assert result.success is True
        client.send_notification.assert_awaited_once()

    async def test_send_returns_transient_when_client_cannot_init(self):
        # Arrange
        sender = ApnsSender()
        broken = MagicMock()
        broken.APNs = MagicMock(side_effect=RuntimeError("no creds"))

        # Act
        with patch.dict(sys.modules, {"aioapns": broken}):
            result = await sender.send(_make_payload(PushPlatform.IOS))

        # Assert
        assert result.success is False
        assert result.error_type is PushErrorType.TRANSIENT
        assert result.error_code == "apns_not_initialized"

    async def test_send_bad_device_token_is_permanent_token(self):
        # Arrange
        sender = ApnsSender()
        client = MagicMock()
        client.send_notification = AsyncMock(
            return_value=SimpleNamespace(
                is_successful=False,
                description="BadDeviceToken",
            ),
        )
        sender._client = client
        fake_aioapns = MagicMock()
        fake_aioapns.NotificationRequest = MagicMock(
            side_effect=lambda **kw: SimpleNamespace(**kw),
        )

        # Act
        with patch.dict(sys.modules, {"aioapns": fake_aioapns}):
            result = await sender.send(_make_payload(PushPlatform.IOS))

        # Assert
        assert result.error_type is PushErrorType.PERMANENT_TOKEN
        assert result.error_code == "BadDeviceToken"

    async def test_send_too_many_requests_is_rate_limit(self):
        # Arrange
        sender = ApnsSender()
        client = MagicMock()
        client.send_notification = AsyncMock(
            return_value=SimpleNamespace(
                is_successful=False,
                description="TooManyRequests",
            ),
        )
        sender._client = client
        fake_aioapns = MagicMock()
        fake_aioapns.NotificationRequest = MagicMock(
            side_effect=lambda **kw: SimpleNamespace(**kw),
        )

        # Act
        with patch.dict(sys.modules, {"aioapns": fake_aioapns}):
            result = await sender.send(_make_payload(PushPlatform.IOS))

        # Assert
        assert result.error_type is PushErrorType.RATE_LIMIT
        assert result.retry_after == 60

    async def test_send_exception_is_transient(self):
        # Arrange
        sender = ApnsSender()
        client = MagicMock()
        client.send_notification = AsyncMock(side_effect=RuntimeError("network"))
        sender._client = client
        fake_aioapns = MagicMock()
        fake_aioapns.NotificationRequest = MagicMock(
            side_effect=lambda **kw: SimpleNamespace(**kw),
        )

        # Act
        with patch.dict(sys.modules, {"aioapns": fake_aioapns}):
            result = await sender.send(_make_payload(PushPlatform.IOS))

        # Assert
        assert result.error_type is PushErrorType.TRANSIENT
        assert "network" in result.error_code


# ---------------------------------------------------------------------------
# send_with_retry
# ---------------------------------------------------------------------------
class TestSendWithRetry:
    async def test_returns_on_first_success(self):
        # Arrange
        sender = MagicMock()
        sender.send = AsyncMock(return_value=PushResult(success=True))

        # Act
        with patch("app.services.push_sender.asyncio.sleep", new=AsyncMock()):
            result = await send_with_retry(sender, _make_payload(), max_attempts=3)

        # Assert
        assert result.success is True
        sender.send.assert_awaited_once()

    async def test_permanent_token_short_circuits_without_retry(self):
        # Arrange
        sender = MagicMock()
        sender.send = AsyncMock(
            return_value=PushResult(
                success=False,
                error_type=PushErrorType.PERMANENT_TOKEN,
                error_code="UNREGISTERED",
            ),
        )

        # Act
        with patch("app.services.push_sender.asyncio.sleep", new=AsyncMock()) as sl:
            result = await send_with_retry(sender, _make_payload(), max_attempts=5)

        # Assert
        assert result.error_type is PushErrorType.PERMANENT_TOKEN
        sender.send.assert_awaited_once()
        sl.assert_not_awaited()

    async def test_rate_limit_sleeps_retry_after_then_retries(self):
        # Arrange
        sender = MagicMock()
        sender.send = AsyncMock(
            side_effect=[
                PushResult(
                    success=False,
                    error_type=PushErrorType.RATE_LIMIT,
                    retry_after=7,
                ),
                PushResult(success=True),
            ],
        )
        sleep_mock = AsyncMock()

        # Act
        with patch("app.services.push_sender.asyncio.sleep", new=sleep_mock):
            result = await send_with_retry(sender, _make_payload(), max_attempts=5)

        # Assert
        assert result.success is True
        assert sender.send.await_count == 2
        sleep_mock.assert_any_await(7)

    async def test_transient_errors_retry_then_exhaust(self):
        # Arrange
        sender = MagicMock()
        sender.send = AsyncMock(
            return_value=PushResult(
                success=False,
                error_type=PushErrorType.TRANSIENT,
                error_code="net",
            ),
        )

        # Act
        with patch("app.services.push_sender.asyncio.sleep", new=AsyncMock()):
            result = await send_with_retry(
                sender,
                _make_payload(),
                max_attempts=3,
                base_delay=0.1,
            )

        # Assert
        assert result.success is False
        assert result.error_type is PushErrorType.TRANSIENT
        assert result.error_code == "max_retries_exhausted"
        assert sender.send.await_count == 3
