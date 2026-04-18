"""Unit tests for `app.services.auth_service.AuthService`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают репозитории,
Redis и внешние зависимости, чтобы проверить именно бизнес-логику.
"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519

from app.config import settings
from app.services.auth_service import AuthService


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_service() -> AuthService:
    """Собирает AuthService c замоканной сессией и репозиториями."""
    session = MagicMock()
    service = AuthService.__new__(AuthService)
    service.session = session
    service.device_repo = MagicMock()
    service.session_repo = MagicMock()
    service.invite_repo = MagicMock()

    # Делаем все методы репозиториев awaitable-моками.
    for repo in (service.device_repo, service.session_repo, service.invite_repo):
        for name in (
            "create",
            "update",
            "get_by_id",
            "get_by_device_id",
            "get_by_code",
            "mark_as_used",
            "create_invite",
            "get_by_refresh_token",
            "get_by_access_token",
            "get_by_field",
        ):
            setattr(repo, name, AsyncMock())
    return service


@pytest.fixture
def service() -> AuthService:
    return _make_service()


@pytest.fixture
def redis_mock():
    """Мокает глобальные redis-хелперы из app.database.redis."""
    with (
        patch("app.services.auth_service.store_challenge", new=AsyncMock(return_value=True)),
        patch("app.services.auth_service.get_challenge", new=AsyncMock()) as get_challenge,
        patch("app.services.auth_service.delete_challenge", new=AsyncMock(return_value=True)),
        patch("app.services.auth_service.RedisClient") as redis_cls,
    ):
        redis_instance = MagicMock()
        redis_instance.get = AsyncMock(return_value=None)
        redis_instance.setex = AsyncMock()
        redis_instance.delete = AsyncMock()
        redis_cls.get_instance = AsyncMock(return_value=redis_instance)
        yield SimpleNamespace(
            get_challenge=get_challenge,
            instance=redis_instance,
        )


# ---------------------------------------------------------------------------
# register
# ---------------------------------------------------------------------------
class TestRegister:
    async def test_register_creates_user_device_and_session(self, service):
        # Arrange
        invite = SimpleNamespace(
            code="INV",
            is_used=False,
            is_admin=False,
            expires_at=datetime.utcnow() + timedelta(days=1),
        )
        service.invite_repo.get_by_code.return_value = invite

        # Act
        result = await service.register(
            username="alice",
            invite_code="INV",
            device_id="dev-1",
            device_name="iPhone",
            identity_key_pub=b"id",
            init_key_pub=b"init",
            credential_data=b"cred",
        )

        # Assert
        assert result["device_type"] == "primary"
        assert uuid.UUID(result["user_id"])  # валидный uuid
        assert uuid.UUID(result["device_id"])
        assert isinstance(result["access_token"], str) and result["access_token"]
        assert isinstance(result["refresh_token"], str) and result["refresh_token"]
        assert result["expires_at"] > int(datetime.utcnow().timestamp())

        service.device_repo.create.assert_awaited_once()
        service.session_repo.create.assert_awaited_once()
        service.invite_repo.mark_as_used.assert_awaited_once()

    async def test_register_with_admin_invite_sets_admin_device_type(self, service):
        # Arrange
        invite = SimpleNamespace(
            code="INV",
            is_used=False,
            is_admin=True,
            expires_at=datetime.utcnow() + timedelta(days=1),
        )
        service.invite_repo.get_by_code.return_value = invite

        # Act
        result = await service.register(
            username="admin",
            invite_code="INV",
            device_id="dev",
            device_name="mac",
            identity_key_pub=b"id",
            init_key_pub=b"init",
            credential_data=b"cred",
        )

        # Assert
        assert result["device_type"] == "admin"

    async def test_register_rejects_missing_invite(self, service):
        # Arrange
        service.invite_repo.get_by_code.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Invalid or expired invite"):
            await service.register(
                username="x",
                invite_code="NOPE",
                device_id="d",
                device_name="d",
                identity_key_pub=b"",
                init_key_pub=b"",
                credential_data=b"",
            )

    async def test_register_rejects_used_invite(self, service):
        # Arrange
        invite = SimpleNamespace(
            code="INV",
            is_used=True,
            is_admin=False,
            expires_at=datetime.utcnow() + timedelta(days=1),
        )
        service.invite_repo.get_by_code.return_value = invite

        # Act / Assert
        with pytest.raises(ValueError, match="Invalid or expired invite"):
            await service.register(
                username="x",
                invite_code="INV",
                device_id="d",
                device_name="d",
                identity_key_pub=b"",
                init_key_pub=b"",
                credential_data=b"",
            )

    async def test_register_rejects_expired_invite(self, service):
        # Arrange
        invite = SimpleNamespace(
            code="INV",
            is_used=False,
            is_admin=False,
            expires_at=datetime.utcnow() - timedelta(minutes=1),
        )
        service.invite_repo.get_by_code.return_value = invite

        # Act / Assert
        with pytest.raises(ValueError, match="Invalid or expired invite"):
            await service.register(
                username="x",
                invite_code="INV",
                device_id="d",
                device_name="d",
                identity_key_pub=b"",
                init_key_pub=b"",
                credential_data=b"",
            )


# ---------------------------------------------------------------------------
# login_init
# ---------------------------------------------------------------------------
class TestLoginInit:
    async def test_login_init_returns_challenge(self, service, redis_mock):
        # Arrange
        service.device_repo.get_by_device_id.return_value = SimpleNamespace(
            id=uuid.uuid4(),
            is_active=True,
        )

        # Act
        result = await service.login_init("dev-1")

        # Assert
        assert result["device_id"] == "dev-1"
        decoded = base64.b64decode(result["challenge"])
        assert len(decoded) == 32
        assert result["expires_at"] >= int(datetime.utcnow().timestamp())

    async def test_login_init_rejects_unknown_device(self, service, redis_mock):
        # Arrange
        service.device_repo.get_by_device_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Device not found"):
            await service.login_init("dev-1")

    async def test_login_init_rejects_inactive_device(self, service, redis_mock):
        # Arrange
        service.device_repo.get_by_device_id.return_value = SimpleNamespace(id=uuid.uuid4(), is_active=False)

        # Act / Assert
        with pytest.raises(ValueError, match="inactive or revoked"):
            await service.login_init("dev-1")


# ---------------------------------------------------------------------------
# login_complete
# ---------------------------------------------------------------------------
class TestLoginComplete:
    async def test_login_complete_happy_path(self, service, redis_mock):
        # Arrange
        priv = ed25519.Ed25519PrivateKey.generate()
        pub_bytes = priv.public_key().public_bytes_raw()
        challenge_bytes = b"\x01" * 32
        signature = priv.sign(challenge_bytes)
        device = SimpleNamespace(
            id=uuid.uuid4(),
            user_id=uuid.uuid4(),
            is_active=True,
            identity_key_pub=pub_bytes,
            device_type="primary",
        )
        service.device_repo.get_by_device_id.return_value = device
        redis_mock.get_challenge.return_value = challenge_bytes

        # Act
        result = await service.login_complete(
            device_id="dev-1",
            challenge=base64.b64encode(challenge_bytes).decode(),
            signature=signature,
            device_name="New Name",
        )

        # Assert
        assert result["user_id"] == str(device.user_id)
        assert result["device_type"] == "primary"
        service.device_repo.update.assert_awaited()
        service.session_repo.create.assert_awaited_once()

    async def test_login_complete_invalid_challenge_format(self, service, redis_mock):
        # Act / Assert
        with pytest.raises(ValueError, match="Invalid challenge format"):
            await service.login_complete(device_id="dev", challenge="not-base64!!!", signature=b"")

    async def test_login_complete_missing_stored_challenge(self, service, redis_mock):
        # Arrange
        redis_mock.get_challenge.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Challenge expired"):
            await service.login_complete(
                device_id="dev",
                challenge=base64.b64encode(b"x" * 32).decode(),
                signature=b"sig",
            )

    async def test_login_complete_challenge_mismatch(self, service, redis_mock):
        # Arrange
        redis_mock.get_challenge.return_value = b"y" * 32

        # Act / Assert
        with pytest.raises(ValueError, match="Challenge mismatch"):
            await service.login_complete(
                device_id="dev",
                challenge=base64.b64encode(b"x" * 32).decode(),
                signature=b"sig",
            )

    async def test_login_complete_invalid_signature(self, service, redis_mock):
        # Arrange
        priv = ed25519.Ed25519PrivateKey.generate()
        pub_bytes = priv.public_key().public_bytes_raw()
        challenge_bytes = b"\x02" * 32
        service.device_repo.get_by_device_id.return_value = SimpleNamespace(
            id=uuid.uuid4(),
            user_id=uuid.uuid4(),
            is_active=True,
            identity_key_pub=pub_bytes,
            device_type="primary",
        )
        redis_mock.get_challenge.return_value = challenge_bytes

        # Act / Assert
        with pytest.raises(ValueError, match="Invalid signature"):
            await service.login_complete(
                device_id="dev",
                challenge=base64.b64encode(challenge_bytes).decode(),
                signature=b"\x00" * 64,
            )


# ---------------------------------------------------------------------------
# refresh_token
# ---------------------------------------------------------------------------
class TestRefreshToken:
    async def test_refresh_issues_new_tokens(self, service):
        # Arrange
        session_row = SimpleNamespace(
            is_revoked=False,
            refresh_expires_at=datetime.utcnow() + timedelta(days=1),
            device_id=uuid.uuid4(),
        )
        service.session_repo.get_by_refresh_token.return_value = session_row
        device = SimpleNamespace(
            id=session_row.device_id,
            user_id=uuid.uuid4(),
            is_active=True,
            device_type="primary",
        )
        service.device_repo.get_by_id.return_value = device

        # Act
        result = await service.refresh_token("old")

        # Assert
        assert result["access_token"]
        assert result["refresh_token"] != "old"
        service.session_repo.update.assert_awaited()
        service.session_repo.create.assert_awaited()

    async def test_refresh_rejects_missing_session(self, service):
        # Arrange
        service.session_repo.get_by_refresh_token.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Session not found"):
            await service.refresh_token("x")

    async def test_refresh_rejects_revoked_session(self, service):
        # Arrange
        service.session_repo.get_by_refresh_token.return_value = SimpleNamespace(
            is_revoked=True,
            refresh_expires_at=datetime.utcnow() + timedelta(days=1),
        )

        # Act / Assert
        with pytest.raises(ValueError, match="revoked"):
            await service.refresh_token("x")

    async def test_refresh_rejects_expired(self, service):
        # Arrange
        service.session_repo.get_by_refresh_token.return_value = SimpleNamespace(
            is_revoked=False,
            refresh_expires_at=datetime.utcnow() - timedelta(seconds=1),
        )

        # Act / Assert
        with pytest.raises(ValueError, match="expired"):
            await service.refresh_token("x")

    async def test_refresh_rejects_inactive_device(self, service):
        # Arrange
        service.session_repo.get_by_refresh_token.return_value = SimpleNamespace(
            is_revoked=False,
            refresh_expires_at=datetime.utcnow() + timedelta(days=1),
            device_id=uuid.uuid4(),
        )
        service.device_repo.get_by_id.return_value = SimpleNamespace(is_active=False)

        # Act / Assert
        with pytest.raises(ValueError, match="Device not found or inactive"):
            await service.refresh_token("x")


# ---------------------------------------------------------------------------
# logout
# ---------------------------------------------------------------------------
class TestLogout:
    async def test_logout_revokes_session(self, service, redis_mock):
        # Arrange
        device_uuid = uuid.uuid4()
        token = jwt.encode(
            {
                "sub": "u",
                "device_id": str(device_uuid),
                "device_type": "primary",
                "type": "access",
                "exp": datetime.utcnow() + timedelta(minutes=5),
            },
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )
        service.session_repo.get_by_field.return_value = SimpleNamespace(device_id=device_uuid)

        # Act
        result = await service.logout(token)

        # Assert
        assert result == {"success": True, "message": "Successfully logged out"}
        service.session_repo.update.assert_awaited()
        redis_mock.instance.delete.assert_awaited()

    async def test_logout_rejects_missing_session(self, service, redis_mock):
        # Arrange
        service.session_repo.get_by_field.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Session not found"):
            await service.logout("token")

    async def test_logout_rejects_device_mismatch(self, service, redis_mock):
        # Arrange
        token = jwt.encode(
            {
                "sub": "u",
                "device_id": str(uuid.uuid4()),
                "device_type": "primary",
                "type": "access",
                "exp": datetime.utcnow() + timedelta(minutes=5),
            },
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )
        service.session_repo.get_by_field.return_value = SimpleNamespace(device_id=uuid.uuid4())  # другой uuid

        # Act / Assert
        with pytest.raises(ValueError, match="device mismatch"):
            await service.logout(token)


# ---------------------------------------------------------------------------
# create_invite
# ---------------------------------------------------------------------------
class TestCreateInvite:
    async def test_create_invite_happy_path(self, service):
        # Arrange
        invite = SimpleNamespace(
            code="ABC",
            created_at=datetime.utcnow(),
            expires_at=datetime.utcnow() + timedelta(days=5),
            is_used=False,
        )
        service.invite_repo.create_invite.return_value = invite

        # Act
        result = await service.create_invite(expires_in_days=5)

        # Assert
        assert result["code"] == "ABC"
        assert result["is_used"] is False
        service.invite_repo.create_invite.assert_awaited_once()

    @pytest.mark.parametrize("days", [0, -1, 366, 1000])
    async def test_create_invite_rejects_invalid_days(self, service, days):
        # Act / Assert
        with pytest.raises(ValueError, match="must be between 1 and 365"):
            await service.create_invite(expires_in_days=days)


# ---------------------------------------------------------------------------
# validate_token
# ---------------------------------------------------------------------------
class TestValidateToken:
    async def test_validate_token_cached_result(self, service, redis_mock):
        # Arrange
        cached = b'{"valid": true, "user_id": "u", "device_id": "d", "device_type": "primary", "expires_at": 1}'
        redis_mock.instance.get.return_value = cached

        # Act
        result = await service.validate_token("tok")

        # Assert
        assert result["valid"] is True
        assert result["user_id"] == "u"

    async def test_validate_token_expired_jwt(self, service, redis_mock):
        # Arrange
        token = jwt.encode(
            {"sub": "u", "exp": datetime.utcnow() - timedelta(seconds=1)},
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )

        # Act
        result = await service.validate_token(token)

        # Assert
        assert result["valid"] is False

    async def test_validate_token_invalid_jwt(self, service, redis_mock):
        # Act
        result = await service.validate_token("not-a-jwt")

        # Assert
        assert result["valid"] is False

    async def test_validate_token_happy_path(self, service, redis_mock):
        # Arrange
        device = SimpleNamespace(
            id=uuid.uuid4(),
            user_id=uuid.uuid4(),
            is_active=True,
            device_type="primary",
        )
        service.session_repo.get_by_access_token.return_value = SimpleNamespace(
            is_revoked=False,
            device_id=device.id,
        )
        service.device_repo.get_by_id.return_value = device
        exp = datetime.utcnow() + timedelta(minutes=10)
        token = jwt.encode(
            {
                "sub": str(device.user_id),
                "device_id": str(device.id),
                "device_type": "primary",
                "type": "access",
                "exp": exp,
            },
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )

        # Act
        result = await service.validate_token(token)

        # Assert
        assert result["valid"] is True
        assert result["user_id"] == str(device.user_id)
        redis_mock.instance.setex.assert_awaited()

    async def test_validate_token_revoked_session(self, service, redis_mock):
        # Arrange
        service.session_repo.get_by_access_token.return_value = SimpleNamespace(
            is_revoked=True,
            device_id=uuid.uuid4(),
        )
        token = jwt.encode(
            {
                "sub": "u",
                "device_id": "d",
                "device_type": "primary",
                "type": "access",
                "exp": datetime.utcnow() + timedelta(minutes=10),
            },
            settings.JWT_SECRET,
            algorithm=settings.JWT_ALGORITHM,
        )

        # Act
        result = await service.validate_token(token)

        # Assert
        assert result["valid"] is False


# ---------------------------------------------------------------------------
# _generate_tokens (внутренний, но критичный)
# ---------------------------------------------------------------------------
class TestGenerateTokens:
    def test_tokens_are_decodable_and_have_expected_claims(self, service):
        # Act
        access, refresh, exp, rexp = service._generate_tokens(user_id="u", device_id="d", device_type="primary")

        # Assert
        access_payload = jwt.decode(access, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])
        refresh_payload = jwt.decode(refresh, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])
        assert access_payload["type"] == "access"
        assert refresh_payload["type"] == "refresh"
        assert access_payload["sub"] == "u"
        assert access_payload["device_id"] == "d"
        assert rexp > exp
