"""Unit tests for orchestrator auth use cases.

Тесты строятся по схеме AAA и мокают gRPC-шлюзы, чтобы проверить именно
логику оркестрации (делегирование, компенсация при сбоях и т.п.).
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from app.core.interfaces.auth_gateway import (
    AuthResult,
    CreateInviteResult,
    LoginCompleteRequest,
    LoginInitResult,
    LogoutResult,
    RegisterRequest,
)
from app.core.interfaces.user_gateway import CreateUserResult
from app.core.use_cases.auth.create_invite import CreateInviteUseCase
from app.core.use_cases.auth.login_complete import LoginCompleteUseCase
from app.core.use_cases.auth.login_init import LoginInitUseCase
from app.core.use_cases.auth.logout import LogoutUseCase
from app.core.use_cases.auth.register import RegisterUseCase
from app.exceptions import GatewayError


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _auth_result(access_token: str = "access") -> AuthResult:
    return AuthResult(
        user_id="user-1",
        device_id="dev-1",
        device_type="primary",
        access_token=access_token,
        refresh_token="refresh",
        expires_at=123,
    )


@pytest.fixture
def auth_gateway() -> MagicMock:
    gw = MagicMock()
    gw.register = AsyncMock()
    gw.login_init = AsyncMock()
    gw.login_complete = AsyncMock()
    gw.logout = AsyncMock()
    gw.create_invite = AsyncMock()
    return gw


@pytest.fixture
def user_gateway() -> MagicMock:
    gw = MagicMock()
    gw.create_user = AsyncMock()
    return gw


# ---------------------------------------------------------------------------
# RegisterUseCase
# ---------------------------------------------------------------------------
class TestRegisterUseCase:
    async def test_happy_path_creates_auth_and_user(self, auth_gateway, user_gateway):
        # Arrange
        auth_gateway.register.return_value = _auth_result()
        user_gateway.create_user.return_value = CreateUserResult(id="user-1", username="alice")
        use_case = RegisterUseCase(auth_gateway, user_gateway)
        request = RegisterRequest(
            username="alice",
            invite_code="INV",
            device_id="dev-1",
            device_name="iPhone",
            identity_key_pub=b"id",
            init_key_pub=b"init",
            credential_data=b"cred",
        )

        # Act
        result = await use_case.execute(request)

        # Assert
        assert result.user_id == "user-1"
        auth_gateway.register.assert_awaited_once_with(request)
        user_gateway.create_user.assert_awaited_once_with(user_id="user-1", username="alice")
        auth_gateway.logout.assert_not_called()

    async def test_user_creation_failure_rolls_back_auth_and_raises(
        self,
        auth_gateway,
        user_gateway,
    ):
        # Arrange
        auth_gateway.register.return_value = _auth_result(access_token="tok-to-revoke")
        user_gateway.create_user.side_effect = RuntimeError("boom")
        use_case = RegisterUseCase(auth_gateway, user_gateway)
        request = RegisterRequest(
            username="alice",
            invite_code="INV",
            device_id="dev-1",
            device_name="iPhone",
            identity_key_pub=b"",
            init_key_pub=b"",
            credential_data=b"",
        )

        # Act / Assert
        with pytest.raises(GatewayError, match="Auth session has been revoked"):
            await use_case.execute(request)

        auth_gateway.logout.assert_awaited_once_with("tok-to-revoke")

    async def test_rollback_logout_failure_is_swallowed(self, auth_gateway, user_gateway):
        # Arrange
        auth_gateway.register.return_value = _auth_result()
        user_gateway.create_user.side_effect = RuntimeError("boom")
        auth_gateway.logout.side_effect = RuntimeError("logout also failed")
        use_case = RegisterUseCase(auth_gateway, user_gateway)
        request = RegisterRequest(
            username="x",
            invite_code="I",
            device_id="d",
            device_name="n",
            identity_key_pub=b"",
            init_key_pub=b"",
            credential_data=b"",
        )

        # Act / Assert
        with pytest.raises(GatewayError):
            await use_case.execute(request)


# ---------------------------------------------------------------------------
# LoginInitUseCase
# ---------------------------------------------------------------------------
class TestLoginInitUseCase:
    async def test_delegates_to_gateway(self, auth_gateway):
        # Arrange
        expected = LoginInitResult(challenge="ch", expires_at=1, device_id="dev-1")
        auth_gateway.login_init.return_value = expected
        use_case = LoginInitUseCase(auth_gateway)

        # Act
        result = await use_case.execute("dev-1")

        # Assert
        assert result is expected
        auth_gateway.login_init.assert_awaited_once_with("dev-1")


# ---------------------------------------------------------------------------
# LoginCompleteUseCase
# ---------------------------------------------------------------------------
class TestLoginCompleteUseCase:
    async def test_builds_request_and_delegates(self, auth_gateway):
        # Arrange
        auth_gateway.login_complete.return_value = _auth_result()
        use_case = LoginCompleteUseCase(auth_gateway)

        # Act
        result = await use_case.execute(
            device_id="dev-1",
            challenge="ch",
            signature=b"sig",
            device_name="iPhone",
        )

        # Assert
        assert result.user_id == "user-1"
        auth_gateway.login_complete.assert_awaited_once()
        passed: LoginCompleteRequest = auth_gateway.login_complete.await_args.args[0]
        assert isinstance(passed, LoginCompleteRequest)
        assert passed.device_id == "dev-1"
        assert passed.challenge == "ch"
        assert passed.signature == b"sig"
        assert passed.device_name == "iPhone"

    async def test_device_name_optional_defaults_to_none(self, auth_gateway):
        # Arrange
        auth_gateway.login_complete.return_value = _auth_result()
        use_case = LoginCompleteUseCase(auth_gateway)

        # Act
        await use_case.execute(device_id="d", challenge="c", signature=b"s")

        # Assert
        passed: LoginCompleteRequest = auth_gateway.login_complete.await_args.args[0]
        assert passed.device_name is None


# ---------------------------------------------------------------------------
# LogoutUseCase
# ---------------------------------------------------------------------------
class TestLogoutUseCase:
    async def test_delegates_to_gateway(self, auth_gateway):
        # Arrange
        auth_gateway.logout.return_value = LogoutResult(success=True, message="ok")
        use_case = LogoutUseCase(auth_gateway)

        # Act
        result = await use_case.execute("access-token")

        # Assert
        assert result.success is True
        auth_gateway.logout.assert_awaited_once_with("access-token")


# ---------------------------------------------------------------------------
# CreateInviteUseCase
# ---------------------------------------------------------------------------
class TestCreateInviteUseCase:
    async def test_delegates_to_gateway_with_defaults(self, auth_gateway):
        # Arrange
        expected = CreateInviteResult(
            code="ABC",
            created_at=0,
            expires_at=1,
            is_used=False,
            message="",
        )
        auth_gateway.create_invite.return_value = expected
        use_case = CreateInviteUseCase(auth_gateway)

        # Act
        result = await use_case.execute(expires_in_days=5)

        # Assert
        assert result is expected
        auth_gateway.create_invite.assert_awaited_once_with(
            expires_in_days=5,
            created_by_device_id=None,
        )

    async def test_passes_device_id_when_provided(self, auth_gateway):
        # Arrange
        auth_gateway.create_invite.return_value = CreateInviteResult(
            code="X",
            created_at=0,
            expires_at=0,
            is_used=False,
            message="",
        )
        use_case = CreateInviteUseCase(auth_gateway)

        # Act
        await use_case.execute(expires_in_days=1, created_by_device_id="dev-42")

        # Assert
        auth_gateway.create_invite.assert_awaited_once_with(
            expires_in_days=1,
            created_by_device_id="dev-42",
        )
