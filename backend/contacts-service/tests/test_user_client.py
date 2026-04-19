"""Unit tests for `app.services.user_client` (gRPC-клиент user-service).

Мокаем gRPC-стаб и пакет `app.generated.user_pb2`, чтобы проверить
корректную обработку ответов, обработку ошибок и закрытие канала.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import grpc
import pytest

from app.services import user_client as user_client_module
from app.services.user_client import UserBriefProfile, UserServiceClient, close_user_channel, get_user_client


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_client() -> UserServiceClient:
    client = UserServiceClient.__new__(UserServiceClient)
    client._stub = MagicMock()
    client._stub.GetUserByUserPublicKey = AsyncMock()
    client._stub.UserExists = AsyncMock()
    client._stub.GetUsersBatch = AsyncMock()
    client._timeout = 1.0
    return client


@pytest.fixture
def client() -> UserServiceClient:
    return _make_client()


def _aio_rpc_error() -> grpc.aio.AioRpcError:
    return grpc.aio.AioRpcError(
        code=grpc.StatusCode.UNAVAILABLE,
        initial_metadata=None,
        trailing_metadata=None,
        details="unavailable",
    )


# ---------------------------------------------------------------------------
# get_user_by_public_key
# ---------------------------------------------------------------------------
class TestGetUserByPublicKey:
    async def test_returns_id_when_present(self, client):
        # Arrange
        profile = MagicMock()
        profile.id = "user-1"
        resp = MagicMock()
        resp.HasField = MagicMock(return_value=True)
        resp.profile = profile
        client._stub.GetUserByUserPublicKey.return_value = resp

        # Act
        result = await client.get_user_by_public_key("pub", "req")

        # Assert
        assert result == "user-1"

    async def test_returns_none_without_profile(self, client):
        resp = MagicMock()
        resp.HasField = MagicMock(return_value=False)
        client._stub.GetUserByUserPublicKey.return_value = resp

        result = await client.get_user_by_public_key("pub", "req")

        assert result is None

    async def test_returns_none_on_rpc_error(self, client):
        client._stub.GetUserByUserPublicKey.side_effect = _aio_rpc_error()
        result = await client.get_user_by_public_key("pub", "req")
        assert result is None


# ---------------------------------------------------------------------------
# user_exists
# ---------------------------------------------------------------------------
class TestUserExists:
    async def test_returns_tuple(self, client):
        resp = MagicMock()
        resp.exists = True
        resp.is_deleted = False
        client._stub.UserExists.return_value = resp

        result = await client.user_exists("u")

        assert result == (True, False)

    async def test_rpc_error_returns_false_false(self, client):
        client._stub.UserExists.side_effect = _aio_rpc_error()
        assert await client.user_exists("u") == (False, False)


# ---------------------------------------------------------------------------
# get_users_batch
# ---------------------------------------------------------------------------
class TestGetUsersBatch:
    async def test_empty_input_short_circuits(self, client):
        result = await client.get_users_batch([])
        assert result == {}
        client._stub.GetUsersBatch.assert_not_called()

    async def test_maps_protos_to_profiles(self, client):
        p1 = MagicMock()
        p1.id = "u1"
        p1.username = "alice"
        p1.avatar_media_id = "a1"
        p2 = MagicMock()
        p2.id = "u2"
        p2.username = "bob"
        p2.avatar_media_id = ""
        resp = MagicMock()
        resp.users = [p1, p2]
        client._stub.GetUsersBatch.return_value = resp

        result = await client.get_users_batch(["u1", "u2"])

        assert set(result.keys()) == {"u1", "u2"}
        assert isinstance(result["u1"], UserBriefProfile)
        assert result["u1"].username == "alice"
        assert result["u2"].avatar_media_id == ""


# ---------------------------------------------------------------------------
# close_user_channel / get_user_client
# ---------------------------------------------------------------------------
class TestChannelLifecycle:
    async def test_close_when_channel_absent_is_noop(self):
        # Arrange
        user_client_module._channel = None

        # Act
        await close_user_channel()

        # Assert
        assert user_client_module._channel is None

    async def test_close_invokes_channel_close(self):
        # Arrange
        fake = MagicMock()
        fake.close = AsyncMock()
        user_client_module._channel = fake

        # Act
        await close_user_channel()

        # Assert
        fake.close.assert_awaited_once()
        assert user_client_module._channel is None

    def test_get_user_client_is_cached(self):
        # Arrange
        get_user_client.cache_clear()
        with patch(
            "app.services.user_client.UserServiceClient",
            autospec=True,
        ) as cls:
            cls.return_value = MagicMock()

            # Act
            c1 = get_user_client()
            c2 = get_user_client()

            # Assert
            assert c1 is c2
            cls.assert_called_once()

        get_user_client.cache_clear()
