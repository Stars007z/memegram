"""Unit tests for `app.services.device_service.DeviceService`.

Тесты используют AAA и мокают репозитории/зависимости.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519

from app.services.device_service import DeviceService


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _make_service() -> DeviceService:
    session = MagicMock()
    session.execute = AsyncMock()
    service = DeviceService.__new__(DeviceService)
    service.session = session
    service.device_repo = MagicMock()
    service.registration_repo = MagicMock()
    service.session_repo = MagicMock()
    for repo in (service.device_repo, service.registration_repo, service.session_repo):
        for name in (
            "create",
            "update",
            "get_by_id",
            "get_by_device_id",
            "get_by_user_id",
            "get_active_registration",
            "get_by_registration_id",
            "get_pending_by_user",
            "get_stats",
        ):
            setattr(repo, name, AsyncMock())

    auth_service = MagicMock()
    auth_service._generate_tokens = MagicMock(
        return_value=(
            "access",
            "refresh",
            datetime.utcnow() + timedelta(minutes=60),
            datetime.utcnow() + timedelta(days=7),
        )
    )
    service._auth_service = auth_service
    return service


@pytest.fixture
def service() -> DeviceService:
    return _make_service()


def _device(**overrides):
    base = dict(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        client_device_id="client-dev",
        device_name="phone",
        device_type="primary",
        is_active=True,
        created_at=datetime.utcnow(),
        last_seen=datetime.utcnow(),
        identity_key_pub=b"id",
        init_key_pub=b"init",
        revoked_at=None,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


# ---------------------------------------------------------------------------
# init_device_addition
# ---------------------------------------------------------------------------
class TestInitDeviceAddition:
    async def test_happy_path(self, service):
        # Arrange
        uid = uuid.uuid4()
        device = _device(user_id=uid, device_type="primary", is_active=True)
        service.device_repo.get_by_device_id.return_value = device

        # Act
        result = await service.init_device_addition(str(uid), "cli-dev")

        # Assert
        assert uuid.UUID(result["registration_id"])
        assert len(result["registration_code"]) == 6
        assert result["expires_at"] > int(datetime.utcnow().timestamp())
        service.registration_repo.create.assert_awaited_once()

    async def test_rejects_unknown_device(self, service):
        service.device_repo.get_by_device_id.return_value = None
        with pytest.raises(ValueError, match="Device not found"):
            await service.init_device_addition(str(uuid.uuid4()), "x")

    async def test_rejects_other_user_device(self, service):
        service.device_repo.get_by_device_id.return_value = _device(user_id=uuid.uuid4())
        with pytest.raises(PermissionError):
            await service.init_device_addition(str(uuid.uuid4()), "x")

    async def test_rejects_non_primary(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, device_type="secondary")
        with pytest.raises(PermissionError, match="Only primary"):
            await service.init_device_addition(str(uid), "x")

    async def test_rejects_inactive(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, device_type="primary", is_active=False)
        with pytest.raises(ValueError, match="inactive"):
            await service.init_device_addition(str(uid), "x")


# ---------------------------------------------------------------------------
# submit_device_data
# ---------------------------------------------------------------------------
class TestSubmitDeviceData:
    async def test_happy_path(self, service):
        # Arrange
        reg = SimpleNamespace(
            registration_code="000000",
            status="pending",
            expires_at=datetime.utcnow() + timedelta(minutes=5),
        )
        service.registration_repo.get_active_registration.return_value = reg

        # Act
        result = await service.submit_device_data(
            registration_id=str(uuid.uuid4()),
            registration_code="000000",
            device_id="d",
            device_name="n",
            device_type="secondary",
            identity_key_pub=b"",
            init_key_pub=b"",
            credential_data=b"",
        )

        # Assert
        assert result["status"] == "awaiting_confirmation"
        service.registration_repo.update.assert_awaited()

    async def test_rejects_unknown_registration(self, service):
        service.registration_repo.get_active_registration.return_value = None
        with pytest.raises(ValueError, match="Registration not found"):
            await service.submit_device_data(str(uuid.uuid4()), "000000", "d", "n", "secondary", b"", b"", b"")

    async def test_rejects_wrong_code(self, service):
        service.registration_repo.get_active_registration.return_value = SimpleNamespace(
            registration_code="111111", status="pending"
        )
        with pytest.raises(ValueError, match="Invalid registration code"):
            await service.submit_device_data(str(uuid.uuid4()), "000000", "d", "n", "secondary", b"", b"", b"")

    async def test_rejects_wrong_state(self, service):
        service.registration_repo.get_active_registration.return_value = SimpleNamespace(
            registration_code="000000", status="confirmed"
        )
        with pytest.raises(ValueError, match="unexpected state"):
            await service.submit_device_data(str(uuid.uuid4()), "000000", "d", "n", "secondary", b"", b"", b"")


# ---------------------------------------------------------------------------
# get_device_addition_status
# ---------------------------------------------------------------------------
class TestGetDeviceAdditionStatus:
    async def test_not_found(self, service):
        service.registration_repo.get_by_registration_id.return_value = None
        with pytest.raises(ValueError, match="Registration not found"):
            await service.get_device_addition_status(str(uuid.uuid4()))

    async def test_pending(self, service):
        # Arrange
        reg = SimpleNamespace(
            status="pending",
            expires_at=datetime.utcnow() + timedelta(minutes=5),
            confirmed_device_id=None,
            result_access_token=None,
        )
        service.registration_repo.get_by_registration_id.return_value = reg

        # Act
        result = await service.get_device_addition_status(str(uuid.uuid4()))

        # Assert
        assert result["status"] == "pending"
        assert result["device"] is None
        assert result["access_token"] == ""

    async def test_confirmed_returns_device_and_tokens(self, service):
        # Arrange
        device = _device()
        reg = SimpleNamespace(
            status="confirmed",
            expires_at=datetime.utcnow() + timedelta(minutes=5),
            confirmed_device_id=device.id,
            result_access_token="a",
            result_refresh_token="r",
            result_token_expires_at=datetime.utcnow() + timedelta(minutes=10),
        )
        service.registration_repo.get_by_registration_id.return_value = reg
        service.device_repo.get_by_id.return_value = device

        # Act
        result = await service.get_device_addition_status(str(uuid.uuid4()))

        # Assert
        assert result["status"] == "confirmed"
        assert result["device"]["id"] == str(device.id)
        assert result["access_token"] == "a"


# ---------------------------------------------------------------------------
# confirm_device_addition
# ---------------------------------------------------------------------------
class TestConfirmDeviceAddition:
    async def test_reject_flow(self, service):
        # Arrange
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, device_type="primary")
        service.registration_repo.get_active_registration.return_value = SimpleNamespace(
            user_id=uid, status="awaiting_confirmation"
        )

        # Act
        result = await service.confirm_device_addition(
            user_id=str(uid),
            device_id="primary-dev",
            registration_id=str(uuid.uuid4()),
            confirm=False,
        )

        # Assert
        assert result["status"] == "rejected"
        service.device_repo.create.assert_not_called()

    async def test_confirm_flow_creates_device_and_session(self, service):
        # Arrange
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        service.device_repo.get_by_device_id.return_value = primary
        reg = SimpleNamespace(
            user_id=uid,
            status="awaiting_confirmation",
            device_id="client-dev",
            device_name="ipad",
            identity_key_pub=b"id",
            init_key_pub=b"init",
            credential_data=b"cred",
        )
        service.registration_repo.get_active_registration.return_value = reg

        # Act
        result = await service.confirm_device_addition(
            user_id=str(uid),
            device_id="primary-dev",
            registration_id=str(uuid.uuid4()),
            confirm=True,
            new_device_name="ipad pro",
        )

        # Assert
        assert result["status"] == "confirmed"
        assert uuid.UUID(result["new_device_id"])
        assert result["access_token"] == "access"
        service.device_repo.create.assert_awaited_once()
        service.session_repo.create.assert_awaited_once()

    async def test_rejects_non_primary(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, device_type="secondary")
        with pytest.raises(PermissionError, match="Only primary"):
            await service.confirm_device_addition(str(uid), "x", str(uuid.uuid4()), confirm=True)

    async def test_rejects_mismatched_reg_user(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, device_type="primary")
        service.registration_repo.get_active_registration.return_value = SimpleNamespace(
            user_id=uuid.uuid4(), status="awaiting_confirmation"
        )
        with pytest.raises(PermissionError):
            await service.confirm_device_addition(str(uid), "x", str(uuid.uuid4()), confirm=True)


# ---------------------------------------------------------------------------
# get_devices / get_device
# ---------------------------------------------------------------------------
class TestGetDevices:
    async def test_get_devices(self, service):
        # Arrange
        devs = [_device(), _device()]
        service.device_repo.get_by_user_id.return_value = devs

        # Act
        result = await service.get_devices(str(uuid.uuid4()))

        # Assert
        assert len(result) == 2
        assert all("id" in d for d in result)

    async def test_get_device_ownership_check(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uuid.uuid4())
        with pytest.raises(PermissionError):
            await service.get_device(str(uid), "dev")


# ---------------------------------------------------------------------------
# revoke_device
# ---------------------------------------------------------------------------
class TestRevokeDevice:
    async def test_happy_path(self, service):
        # Arrange
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        target = _device(user_id=uid, device_type="secondary", is_active=True)
        service.device_repo.get_by_device_id.side_effect = [primary, target]

        # Act
        result = await service.revoke_device(
            user_id=str(uid),
            requesting_device_id="req",
            target_device_id="tgt",
            reason="lost",
        )

        # Assert
        assert result["success"] is True
        service.device_repo.update.assert_awaited()
        service.session.execute.assert_awaited()

    async def test_rejects_revoking_primary(self, service):
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        target = _device(user_id=uid, device_type="primary")
        service.device_repo.get_by_device_id.side_effect = [primary, target]
        with pytest.raises(ValueError, match="Cannot revoke primary"):
            await service.revoke_device(str(uid), "r", "t", "x")

    async def test_rejects_already_revoked(self, service):
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        target = _device(user_id=uid, device_type="secondary", is_active=False)
        service.device_repo.get_by_device_id.side_effect = [primary, target]
        with pytest.raises(ValueError, match="already revoked"):
            await service.revoke_device(str(uid), "r", "t", "x")


# ---------------------------------------------------------------------------
# update_device_keys
# ---------------------------------------------------------------------------
class TestUpdateDeviceKeys:
    async def test_happy_path(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid)
        result = await service.update_device_keys(str(uid), "d", b"i", b"k", b"c")
        assert result["success"] is True
        service.device_repo.update.assert_awaited()

    async def test_rejects_inactive(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, is_active=False)
        with pytest.raises(ValueError, match="inactive"):
            await service.update_device_keys(str(uid), "d", b"", b"", b"")


# ---------------------------------------------------------------------------
# rename_device
# ---------------------------------------------------------------------------
class TestRenameDevice:
    async def test_self_rename_allowed(self, service):
        uid = uuid.uuid4()
        dev = _device(user_id=uid, device_type="secondary")
        service.device_repo.get_by_device_id.side_effect = [dev, dev]
        result = await service.rename_device(str(uid), "d", "d", "new")
        assert result["new_name"] == "new"

    async def test_primary_can_rename_others(self, service):
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        other = _device(user_id=uid, device_type="secondary")
        service.device_repo.get_by_device_id.side_effect = [primary, other]
        result = await service.rename_device(str(uid), "p", "o", "x")
        assert result["success"] is True

    async def test_secondary_cannot_rename_others(self, service):
        uid = uuid.uuid4()
        sec = _device(user_id=uid, device_type="secondary")
        other = _device(user_id=uid, device_type="secondary")
        service.device_repo.get_by_device_id.side_effect = [sec, other]
        with pytest.raises(PermissionError):
            await service.rename_device(str(uid), "s", "o", "x")


# ---------------------------------------------------------------------------
# verify_device
# ---------------------------------------------------------------------------
class TestVerifyDevice:
    async def test_valid_signature(self, service):
        priv = ed25519.Ed25519PrivateKey.generate()
        pub = priv.public_key().public_bytes_raw()
        device_id = "dev-1"
        sig = priv.sign(device_id.encode("utf-8"))
        service.device_repo.get_by_device_id.return_value = _device(identity_key_pub=pub)

        result = await service.verify_device(device_id, sig)

        assert result == {"valid": True, "message": "Device verified successfully"}

    async def test_invalid_signature(self, service):
        priv = ed25519.Ed25519PrivateKey.generate()
        pub = priv.public_key().public_bytes_raw()
        service.device_repo.get_by_device_id.return_value = _device(identity_key_pub=pub)
        result = await service.verify_device("dev", b"\x00" * 64)
        assert result["valid"] is False

    async def test_unknown_device(self, service):
        service.device_repo.get_by_device_id.return_value = None
        with pytest.raises(ValueError, match="Device not found"):
            await service.verify_device("dev", b"")


# ---------------------------------------------------------------------------
# transfer_primary
# ---------------------------------------------------------------------------
class TestTransferPrimary:
    async def test_happy_path(self, service):
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        target = _device(user_id=uid, device_type="secondary", is_active=True)
        service.device_repo.get_by_device_id.side_effect = [primary, target]

        result = await service.transfer_primary(str(uid), "p", "t")

        assert result["success"] is True
        assert service.device_repo.update.await_count == 2

    async def test_cannot_transfer_to_self(self, service):
        uid = uuid.uuid4()
        shared_id = uuid.uuid4()
        primary = _device(id=shared_id, user_id=uid, device_type="primary")
        target = _device(id=shared_id, user_id=uid, device_type="primary", is_active=True)
        service.device_repo.get_by_device_id.side_effect = [primary, target]
        with pytest.raises(ValueError, match="same device"):
            await service.transfer_primary(str(uid), "p", "t")

    async def test_rejects_non_primary_caller(self, service):
        uid = uuid.uuid4()
        service.device_repo.get_by_device_id.return_value = _device(user_id=uid, device_type="secondary")
        with pytest.raises(PermissionError):
            await service.transfer_primary(str(uid), "p", "t")


# ---------------------------------------------------------------------------
# bulk_revoke_devices
# ---------------------------------------------------------------------------
class TestBulkRevoke:
    async def test_skips_invalid_and_revokes_rest(self, service):
        # Arrange
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary")
        good = _device(user_id=uid, device_type="secondary", is_active=True)
        other_user = _device(user_id=uuid.uuid4(), device_type="secondary", is_active=True)
        primary_target = _device(user_id=uid, device_type="primary", is_active=True)
        already = _device(user_id=uid, device_type="secondary", is_active=False)

        service.device_repo.get_by_device_id.side_effect = [
            primary,
            good,
            None,
            other_user,
            primary_target,
            already,
        ]

        # Act
        result = await service.bulk_revoke_devices(
            user_id=str(uid),
            requesting_device_id="p",
            target_device_ids=["g", "missing", "other", "primary", "already"],
            reason="rotate",
        )

        # Assert
        assert result["revoked_count"] == 1
        assert result["revoked_device_ids"] == [str(good.id)]

    async def test_account_deleted_system_purge_revokes_all_including_primary(self, service):
        # System path: orchestrator passes empty requesting_device_id with
        # reason="account_deleted" — owner check is skipped and the primary
        # device must also be revoked.
        uid = uuid.uuid4()
        primary = _device(user_id=uid, device_type="primary", is_active=True)
        secondary = _device(user_id=uid, device_type="secondary", is_active=True)
        other_user = _device(user_id=uuid.uuid4(), device_type="secondary", is_active=True)

        service.device_repo.get_by_device_id.side_effect = [primary, secondary, other_user]

        result = await service.bulk_revoke_devices(
            user_id=str(uid),
            requesting_device_id="",
            target_device_ids=["p", "s", "other"],
            reason="account_deleted",
        )

        assert result["revoked_count"] == 2
        assert set(result["revoked_device_ids"]) == {str(primary.id), str(secondary.id)}
        # Update was called for each (not for the foreign-user device).
        assert service.device_repo.update.await_count == 2


# ---------------------------------------------------------------------------
# get_device_stats
# ---------------------------------------------------------------------------
class TestGetDeviceStats:
    async def test_stats(self, service):
        # Arrange
        now = datetime.utcnow()
        service.device_repo.get_stats.return_value = {
            "total_count": 3,
            "active_count": 2,
            "primary_count": 1,
            "type_stats": [{"device_type": "primary", "count": 1}],
            "last_activity_at": now,
        }

        # Act
        result = await service.get_device_stats(str(uuid.uuid4()))

        # Assert
        assert result["total_count"] == 3
        assert result["active_count"] == 2
        assert result["last_activity_at"] == int(now.timestamp())

    async def test_stats_with_no_activity(self, service):
        service.device_repo.get_stats.return_value = {
            "total_count": 0,
            "active_count": 0,
            "primary_count": 0,
            "type_stats": [],
            "last_activity_at": None,
        }
        result = await service.get_device_stats(str(uuid.uuid4()))
        assert result["last_activity_at"] == 0
