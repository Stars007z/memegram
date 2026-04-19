"""Unit tests for `app.services.mls_service.MlsServiceImpl`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают репозитории
MLS, auth-client, Redis и IStreamService.
"""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest
from sqlalchemy.exc import IntegrityError

from app.services.interfaces.stream_service import IStreamService
from app.services.mls_service import MlsServiceImpl


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _repo(*names: str) -> MagicMock:
    r = MagicMock()
    for n in names:
        setattr(r, n, AsyncMock())
    return r


class _NestedCtx:
    """Асинхронный контекст-менеджер, имитирующий session.begin_nested()."""

    def __init__(self, *, raise_integrity: bool = False) -> None:
        self._raise_integrity = raise_integrity

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False


@pytest.fixture
def key_package_repo() -> MagicMock:
    return _repo(
        "delete_by_device",
        "create_many",
        "consume_one",
        "count_available",
    )


@pytest.fixture
def mls_group_repo() -> MagicMock:
    return _repo("get_by_conversation_id", "update")


@pytest.fixture
def welcome_repo() -> MagicMock:
    return _repo("create", "get_pending_for_device", "get_by_id", "update")


@pytest.fixture
def commit_repo() -> MagicMock:
    repo = _repo("create", "get_since_epoch")
    repo.session = MagicMock()
    repo.session.begin_nested = MagicMock(return_value=_NestedCtx())
    return repo


@pytest.fixture
def member_repo() -> MagicMock:
    return _repo(
        "has_role",
        "get_member",
        "create",
        "update",
        "get_user_conversation_ids",
    )


@pytest.fixture
def auth_client() -> MagicMock:
    client = MagicMock()
    client.get_active_device_ids = AsyncMock()
    return client


@pytest.fixture
def redis_mock() -> MagicMock:
    return MagicMock()


@pytest.fixture
def stream_mock() -> MagicMock:
    stream = MagicMock(spec=IStreamService)
    stream.publish_event = AsyncMock()
    return stream


@pytest.fixture
def service(
    key_package_repo,
    mls_group_repo,
    welcome_repo,
    commit_repo,
    member_repo,
    auth_client,
    redis_mock,
    stream_mock,
) -> MlsServiceImpl:
    return MlsServiceImpl(
        key_package_repo,
        mls_group_repo,
        welcome_repo,
        commit_repo,
        member_repo,
        auth_client,
        redis_mock,
        stream_mock,
    )


# ---------------------------------------------------------------------------
# upload_key_packages
# ---------------------------------------------------------------------------
class TestUploadKeyPackages:
    async def test_happy_path_purges_and_creates(
        self,
        service,
        key_package_repo,
    ):
        # Arrange
        key_package_repo.delete_by_device.return_value = 3
        kp_bytes = [b"one", b"two"]
        key_package_repo.create_many.return_value = [object(), object()]

        # Act
        count = await service.upload_key_packages(
            uuid.uuid4(),
            uuid.uuid4(),
            kp_bytes,
        )

        # Assert
        assert count == 2
        key_package_repo.delete_by_device.assert_awaited_once()
        # Проверяем, что ref = sha256(data):
        items = key_package_repo.create_many.await_args.args[0]
        assert items[0]["key_package_ref"] == hashlib.sha256(b"one").digest()

    async def test_no_packages_creates_empty(
        self,
        service,
        key_package_repo,
    ):
        # Arrange
        key_package_repo.delete_by_device.return_value = 0
        key_package_repo.create_many.return_value = []

        # Act
        count = await service.upload_key_packages(
            uuid.uuid4(),
            uuid.uuid4(),
            [],
        )

        # Assert
        assert count == 0


# ---------------------------------------------------------------------------
# delete_key_packages_for_device
# ---------------------------------------------------------------------------
class TestDeleteKeyPackagesForDevice:
    async def test_returns_deleted_count(self, service, key_package_repo):
        # Arrange
        key_package_repo.delete_by_device.return_value = 5

        # Act
        result = await service.delete_key_packages_for_device(
            uuid.uuid4(),
            uuid.uuid4(),
        )

        # Assert
        assert result == 5


# ---------------------------------------------------------------------------
# get_key_package
# ---------------------------------------------------------------------------
class TestGetKeyPackage:
    async def test_happy_path(self, service, key_package_repo):
        # Arrange
        pkg = SimpleNamespace(key_package_data=b"data", key_package_ref=b"ref")
        key_package_repo.consume_one.return_value = pkg

        # Act
        result = await service.get_key_package(uuid.uuid4(), uuid.uuid4())

        # Assert
        assert result.key_package_data == b"data"
        assert result.key_package_ref == b"ref"

    async def test_not_found_raises(self, service, key_package_repo):
        # Arrange
        key_package_repo.consume_one.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="No available key packages"):
            await service.get_key_package(uuid.uuid4(), uuid.uuid4())


# ---------------------------------------------------------------------------
# get_key_packages_count
# ---------------------------------------------------------------------------
class TestGetKeyPackagesCount:
    async def test_returns_repo_result(self, service, key_package_repo):
        # Arrange
        key_package_repo.count_available.return_value = 7

        # Act
        result = await service.get_key_packages_count(uuid.uuid4(), uuid.uuid4())

        # Assert
        assert result == 7


# ---------------------------------------------------------------------------
# get_key_packages_for_user
# ---------------------------------------------------------------------------
class TestGetKeyPackagesForUser:
    async def test_happy_path(self, service, auth_client, key_package_repo):
        # Arrange
        d1 = uuid.uuid4()
        d2 = uuid.uuid4()
        auth_client.get_active_device_ids.return_value = [d1, d2]
        key_package_repo.consume_one.side_effect = [
            SimpleNamespace(key_package_data=b"a", key_package_ref=b"ra"),
            SimpleNamespace(key_package_data=b"b", key_package_ref=b"rb"),
        ]

        # Act
        results = await service.get_key_packages_for_user(uuid.uuid4())

        # Assert
        assert [r.device_id for r in results] == [d1, d2]
        assert results[0].key_package_data == b"a"

    async def test_no_devices_raises(self, service, auth_client):
        # Arrange
        auth_client.get_active_device_ids.return_value = []

        # Act / Assert
        with pytest.raises(ValueError, match="No active devices"):
            await service.get_key_packages_for_user(uuid.uuid4())

    async def test_no_packages_on_any_device_raises(
        self,
        service,
        auth_client,
        key_package_repo,
    ):
        # Arrange
        auth_client.get_active_device_ids.return_value = [uuid.uuid4()]
        key_package_repo.consume_one.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="No available key packages"):
            await service.get_key_packages_for_user(uuid.uuid4())


# ---------------------------------------------------------------------------
# commit_group_change
# ---------------------------------------------------------------------------
class TestCommitGroupChange:
    async def test_happy_path_increments_epoch(
        self,
        service,
        mls_group_repo,
        commit_repo,
        stream_mock,
    ):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=3,
        )

        # Act
        result = await service.commit_group_change(
            user_id=uuid.uuid4(),
            device_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            commit_data=b"ct",
            new_epoch=4,
        )

        # Assert
        assert result.new_epoch == 4
        commit_repo.create.assert_awaited_once()
        mls_group_repo.update.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()
        assert stream_mock.publish_event.await_args.args[1]["event_type"] == "epoch_changed"

    async def test_missing_group(self, service, mls_group_repo):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="MLS group not found"):
            await service.commit_group_change(
                user_id=uuid.uuid4(),
                device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                commit_data=b"",
                new_epoch=1,
            )

    async def test_epoch_conflict(self, service, mls_group_repo):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=3,
        )

        # Act / Assert
        with pytest.raises(ValueError, match="Epoch conflict"):
            await service.commit_group_change(
                user_id=uuid.uuid4(),
                device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                commit_data=b"",
                new_epoch=10,
            )

    async def test_non_admin_cannot_add_members(
        self,
        service,
        mls_group_repo,
        member_repo,
    ):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=3,
        )
        member_repo.has_role.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="Only admins"):
            await service.commit_group_change(
                user_id=uuid.uuid4(),
                device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                commit_data=b"",
                new_epoch=4,
                added_user_ids=[uuid.uuid4()],
            )

    async def test_duplicate_commit_raises_aborted(
        self,
        service,
        mls_group_repo,
        commit_repo,
    ):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=3,
        )
        commit_repo.create.side_effect = IntegrityError("i", {}, Exception("x"))

        # Act / Assert
        with pytest.raises(ValueError, match="another commit already exists"):
            await service.commit_group_change(
                user_id=uuid.uuid4(),
                device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                commit_data=b"",
                new_epoch=4,
            )

    async def test_added_user_new_creates_member(
        self,
        service,
        mls_group_repo,
        member_repo,
        stream_mock,
    ):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=0,
        )
        member_repo.has_role.return_value = True
        member_repo.get_member.return_value = None

        # Act
        await service.commit_group_change(
            user_id=uuid.uuid4(),
            device_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            commit_data=b"",
            new_epoch=1,
            added_user_ids=[uuid.uuid4()],
        )

        # Assert
        member_repo.create.assert_awaited_once()
        # Два события: member_joined + epoch_changed
        assert stream_mock.publish_event.await_count == 2

    async def test_added_user_rejoin_resets_left_at(
        self,
        service,
        mls_group_repo,
        member_repo,
    ):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=0,
        )
        member_repo.has_role.return_value = True
        existing = SimpleNamespace(left_at=datetime.utcnow())
        member_repo.get_member.return_value = existing

        # Act
        await service.commit_group_change(
            user_id=uuid.uuid4(),
            device_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            commit_data=b"",
            new_epoch=1,
            added_user_ids=[uuid.uuid4()],
        )

        # Assert
        member_repo.update.assert_awaited_once()
        update_payload = member_repo.update.await_args.args[1]
        assert update_payload["left_at"] is None

    async def test_welcome_messages_are_persisted(
        self,
        service,
        mls_group_repo,
        welcome_repo,
    ):
        # Arrange
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=0,
        )
        wm = [(uuid.uuid4(), b"w1"), (uuid.uuid4(), b"w2")]

        # Act
        await service.commit_group_change(
            user_id=uuid.uuid4(),
            device_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            commit_data=b"",
            new_epoch=1,
            welcome_messages=wm,
        )

        # Assert
        assert welcome_repo.create.await_count == 2


# ---------------------------------------------------------------------------
# get_pending_welcomes / ack_welcome
# ---------------------------------------------------------------------------
class TestWelcomes:
    async def test_get_pending_welcomes_maps_rows(self, service, welcome_repo):
        # Arrange
        now = datetime.utcnow()
        welcome_repo.get_pending_for_device.return_value = [
            SimpleNamespace(
                id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                welcome_data=b"w",
                created_at=now,
            ),
        ]

        # Act
        result = await service.get_pending_welcomes(uuid.uuid4())

        # Assert
        assert len(result) == 1
        assert result[0].welcome_data == b"w"

    async def test_ack_welcome_happy_path(self, service, welcome_repo):
        # Arrange
        device_id = uuid.uuid4()
        welcome = SimpleNamespace(recipient_device_id=device_id)
        welcome_repo.get_by_id.return_value = welcome

        # Act
        ok = await service.ack_welcome(device_id, uuid.uuid4())

        # Assert
        assert ok is True
        welcome_repo.update.assert_awaited_once()

    async def test_ack_welcome_missing(self, service, welcome_repo):
        # Arrange
        welcome_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Welcome message not found"):
            await service.ack_welcome(uuid.uuid4(), uuid.uuid4())

    async def test_ack_welcome_wrong_device(self, service, welcome_repo):
        # Arrange
        welcome_repo.get_by_id.return_value = SimpleNamespace(
            recipient_device_id=uuid.uuid4(),
        )

        # Act / Assert
        with pytest.raises(ValueError, match="Welcome message not found"):
            await service.ack_welcome(uuid.uuid4(), uuid.uuid4())


# ---------------------------------------------------------------------------
# get_pending_commits
# ---------------------------------------------------------------------------
class TestGetPendingCommits:
    async def test_maps_rows(self, service, commit_repo):
        # Arrange
        commit_repo.get_since_epoch.return_value = [
            SimpleNamespace(
                epoch=2,
                commit_data=b"c",
                created_at=datetime.utcnow(),
            ),
        ]

        # Act
        result = await service.get_pending_commits(uuid.uuid4(), 1)

        # Assert
        assert result[0].epoch == 2


# ---------------------------------------------------------------------------
# notify_device_revoked
# ---------------------------------------------------------------------------
class TestNotifyDeviceRevoked:
    async def test_publishes_for_all_conversations(
        self,
        service,
        member_repo,
        stream_mock,
    ):
        # Arrange
        conv_ids = [uuid.uuid4(), uuid.uuid4()]
        member_repo.get_user_conversation_ids.return_value = conv_ids

        # Act
        count = await service.notify_device_revoked(uuid.uuid4(), uuid.uuid4())

        # Assert
        assert count == 2
        assert stream_mock.publish_event.await_count == 2

    async def test_no_conversations_returns_zero(
        self,
        service,
        member_repo,
        stream_mock,
    ):
        # Arrange
        member_repo.get_user_conversation_ids.return_value = []

        # Act
        count = await service.notify_device_revoked(uuid.uuid4(), uuid.uuid4())

        # Assert
        assert count == 0
        stream_mock.publish_event.assert_not_awaited()
