"""Unit tests for `app.services.message_service.MessageServiceImpl`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают репозитории,
Redis, IStreamService, IMediaService и IContactsClient, чтобы проверить
именно бизнес-логику отправки/чтения/редактирования сообщений.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services.interfaces.media_service import IMediaService
from app.services.interfaces.stream_service import IStreamService
from app.services.message_service import MessageServiceImpl


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _async_repo(*names: str) -> MagicMock:
    repo = MagicMock()
    for n in names:
        setattr(repo, n, AsyncMock())
    return repo


def _make_msg(**overrides) -> SimpleNamespace:
    base = dict(
        id=uuid.uuid4(),
        conversation_id=uuid.uuid4(),
        sender_user_id=uuid.uuid4(),
        sender_device_id=uuid.uuid4(),
        type="text",
        mls_ciphertext=b"ct",
        media_id=None,
        reply_to_message_id=None,
        mls_epoch=1,
        created_at=datetime.utcnow(),
        edited_at=None,
        deleted_at=None,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


@pytest.fixture
def message_repo() -> MagicMock:
    return _async_repo(
        "create",
        "update",
        "get_by_id",
        "get_by_client_message_id",
        "get_messages_before",
    )


@pytest.fixture
def member_repo() -> MagicMock:
    return _async_repo(
        "is_member",
        "get_active_member",
        "get_active_members",
        "has_role",
        "update",
    )


@pytest.fixture
def conversation_repo() -> MagicMock:
    return _async_repo("get_by_id", "update_last_message")


@pytest.fixture
def redis_mock() -> MagicMock:
    redis = MagicMock()
    redis.incr = AsyncMock()
    redis.delete = AsyncMock()
    return redis


@pytest.fixture
def stream_mock() -> MagicMock:
    stream = MagicMock(spec=IStreamService)
    stream.publish_event = AsyncMock()
    return stream


@pytest.fixture
def media_mock() -> MagicMock:
    media = MagicMock(spec=IMediaService)
    media.delete_media = AsyncMock()
    return media


@pytest.fixture
def contacts_mock() -> MagicMock:
    contacts = MagicMock()
    contacts.is_blocked = AsyncMock(return_value=False)
    return contacts


@pytest.fixture
def service(
    message_repo,
    member_repo,
    conversation_repo,
    redis_mock,
    stream_mock,
    media_mock,
    contacts_mock,
) -> MessageServiceImpl:
    return MessageServiceImpl(
        message_repo,
        member_repo,
        conversation_repo,
        redis_mock,
        stream_mock,
        media_mock,
        contacts_mock,
    )


# ---------------------------------------------------------------------------
# send_message
# ---------------------------------------------------------------------------
class TestSendMessage:
    async def test_happy_path_group(
        self,
        service,
        member_repo,
        conversation_repo,
        message_repo,
        stream_mock,
    ):
        # Arrange
        sender_id = uuid.uuid4()
        conv_id = uuid.uuid4()
        client_id = uuid.uuid4()
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = SimpleNamespace(type="group")
        message_repo.get_by_client_message_id.return_value = None
        created = _make_msg(
            conversation_id=conv_id,
            sender_user_id=sender_id,
        )
        message_repo.create.return_value = created
        member_repo.get_active_members.return_value = [
            SimpleNamespace(user_id=sender_id),
            SimpleNamespace(user_id=uuid.uuid4()),
        ]

        # Act
        result = await service.send_message(
            sender_user_id=sender_id,
            sender_device_id=uuid.uuid4(),
            conversation_id=conv_id,
            mls_ciphertext=b"ct",
            type="text",
            client_message_id=client_id,
        )

        # Assert
        assert result.message_id == created.id
        message_repo.create.assert_awaited_once()
        conversation_repo.update_last_message.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()
        assert stream_mock.publish_event.await_args.args[1]["event_type"] == "new_message"

    async def test_idempotent_returns_existing_without_creating(
        self,
        service,
        member_repo,
        conversation_repo,
        message_repo,
        stream_mock,
    ):
        # Arrange
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = SimpleNamespace(type="group")
        existing = _make_msg()
        message_repo.get_by_client_message_id.return_value = existing

        # Act
        result = await service.send_message(
            sender_user_id=uuid.uuid4(),
            sender_device_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            mls_ciphertext=b"ct",
            type="text",
            client_message_id=uuid.uuid4(),
        )

        # Assert
        assert result.message_id == existing.id
        message_repo.create.assert_not_awaited()
        stream_mock.publish_event.assert_not_awaited()

    async def test_rejects_non_member(self, service, member_repo):
        # Arrange
        member_repo.is_member.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="PERMISSION_DENIED"):
            await service.send_message(
                sender_user_id=uuid.uuid4(),
                sender_device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                mls_ciphertext=b"",
                type="text",
                client_message_id=uuid.uuid4(),
            )

    async def test_direct_blocked_by_peer(
        self,
        service,
        member_repo,
        conversation_repo,
        contacts_mock,
    ):
        # Arrange
        sender = uuid.uuid4()
        peer = uuid.uuid4()
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = SimpleNamespace(type="direct")
        member_repo.get_active_members.return_value = [
            SimpleNamespace(user_id=sender),
            SimpleNamespace(user_id=peer),
        ]

        async def is_blocked(a, b):
            return a == peer and b == sender

        contacts_mock.is_blocked.side_effect = is_blocked

        # Act / Assert
        with pytest.raises(ValueError, match="blocked by this user"):
            await service.send_message(
                sender_user_id=sender,
                sender_device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                mls_ciphertext=b"",
                type="text",
                client_message_id=uuid.uuid4(),
            )

    async def test_direct_blocked_the_peer(
        self,
        service,
        member_repo,
        conversation_repo,
        contacts_mock,
    ):
        # Arrange
        sender = uuid.uuid4()
        peer = uuid.uuid4()
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = SimpleNamespace(type="direct")
        member_repo.get_active_members.return_value = [
            SimpleNamespace(user_id=sender),
            SimpleNamespace(user_id=peer),
        ]

        async def is_blocked(a, b):
            return a == sender and b == peer

        contacts_mock.is_blocked.side_effect = is_blocked

        # Act / Assert
        with pytest.raises(ValueError, match="You have blocked"):
            await service.send_message(
                sender_user_id=sender,
                sender_device_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                mls_ciphertext=b"",
                type="text",
                client_message_id=uuid.uuid4(),
            )


# ---------------------------------------------------------------------------
# get_messages
# ---------------------------------------------------------------------------
class TestGetMessages:
    async def test_happy_path(self, service, member_repo, message_repo):
        # Arrange
        member_repo.is_member.return_value = True
        msgs = [_make_msg(), _make_msg()]
        message_repo.get_messages_before.return_value = msgs

        # Act
        result = await service.get_messages(
            user_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            limit=50,
        )

        # Assert
        assert len(result.messages) == 2
        assert result.has_more is False
        # _to_result выдал корректные поля:
        first = result.messages[0]
        assert first.id == msgs[0].id
        assert first.type == "text"

    async def test_caps_limit_at_100(self, service, member_repo, message_repo):
        # Arrange
        member_repo.is_member.return_value = True
        message_repo.get_messages_before.return_value = []

        # Act
        await service.get_messages(
            user_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            limit=999,
        )

        # Assert
        assert message_repo.get_messages_before.await_args.args[2] == 100

    async def test_non_member_rejected(self, service, member_repo):
        # Arrange
        member_repo.is_member.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="PERMISSION_DENIED"):
            await service.get_messages(
                user_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                limit=10,
            )


# ---------------------------------------------------------------------------
# edit_message
# ---------------------------------------------------------------------------
class TestEditMessage:
    async def test_happy_path(self, service, message_repo, stream_mock):
        # Arrange
        user_id = uuid.uuid4()
        msg = _make_msg(sender_user_id=user_id)
        message_repo.get_by_id.return_value = msg

        # Act
        result = await service.edit_message(user_id, msg.id, b"newct")

        # Assert
        assert result.id == msg.id
        message_repo.update.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()
        assert stream_mock.publish_event.await_args.args[1]["event_type"] == "message_edited"

    async def test_not_found(self, service, message_repo):
        # Arrange
        message_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.edit_message(uuid.uuid4(), uuid.uuid4(), b"x")

    async def test_not_owner(self, service, message_repo):
        # Arrange
        message_repo.get_by_id.return_value = _make_msg()

        # Act / Assert
        with pytest.raises(ValueError, match="edit own messages"):
            await service.edit_message(uuid.uuid4(), uuid.uuid4(), b"x")

    async def test_already_deleted(self, service, message_repo):
        # Arrange
        user_id = uuid.uuid4()
        message_repo.get_by_id.return_value = _make_msg(
            sender_user_id=user_id,
            deleted_at=datetime.utcnow(),
        )

        # Act / Assert
        with pytest.raises(ValueError, match="has been deleted"):
            await service.edit_message(user_id, uuid.uuid4(), b"x")


# ---------------------------------------------------------------------------
# delete_message
# ---------------------------------------------------------------------------
class TestDeleteMessage:
    async def test_self_delete_for_everyone(
        self,
        service,
        message_repo,
        member_repo,
        stream_mock,
    ):
        # Arrange
        user_id = uuid.uuid4()
        msg = _make_msg(sender_user_id=user_id)
        message_repo.get_by_id.return_value = msg

        # Act
        ok = await service.delete_message(user_id, msg.id, delete_for_everyone=True)

        # Assert
        assert ok is True
        message_repo.update.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()
        assert stream_mock.publish_event.await_args.args[1]["event_type"] == "message_deleted"

    async def test_admin_can_delete_for_everyone(
        self,
        service,
        message_repo,
        member_repo,
    ):
        # Arrange
        user_id = uuid.uuid4()
        msg = _make_msg()  # другой отправитель
        message_repo.get_by_id.return_value = msg
        member_repo.has_role.return_value = True

        # Act
        ok = await service.delete_message(user_id, msg.id, delete_for_everyone=True)

        # Assert
        assert ok is True

    async def test_non_admin_cannot_delete_others_for_everyone(
        self,
        service,
        message_repo,
        member_repo,
    ):
        # Arrange
        message_repo.get_by_id.return_value = _make_msg()
        member_repo.has_role.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="PERMISSION_DENIED"):
            await service.delete_message(
                uuid.uuid4(),
                uuid.uuid4(),
                delete_for_everyone=True,
            )

    async def test_delete_for_self_any_user(
        self,
        service,
        message_repo,
        member_repo,
    ):
        # Arrange — delete_for_everyone=False не требует проверки прав
        message_repo.get_by_id.return_value = _make_msg()

        # Act
        ok = await service.delete_message(
            uuid.uuid4(),
            uuid.uuid4(),
            delete_for_everyone=False,
        )

        # Assert
        assert ok is True
        member_repo.has_role.assert_not_awaited()

    async def test_not_found(self, service, message_repo):
        # Arrange
        message_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.delete_message(uuid.uuid4(), uuid.uuid4(), False)

    async def test_media_delete_swallows_errors(
        self,
        service,
        message_repo,
        media_mock,
    ):
        # Arrange
        user_id = uuid.uuid4()
        msg = _make_msg(sender_user_id=user_id, media_id=uuid.uuid4())
        message_repo.get_by_id.return_value = msg
        media_mock.delete_media.side_effect = RuntimeError("s3 down")

        # Act — ошибка в media_client не роняет delete_message
        ok = await service.delete_message(user_id, msg.id, delete_for_everyone=True)

        # Assert
        assert ok is True
        media_mock.delete_media.assert_awaited_once_with(msg.media_id)


# ---------------------------------------------------------------------------
# mark_as_read
# ---------------------------------------------------------------------------
class TestMarkAsRead:
    async def test_happy_path(self, service, member_repo, redis_mock):
        # Arrange
        member = SimpleNamespace(user_id=uuid.uuid4())
        member_repo.get_active_member.return_value = member

        # Act
        result = await service.mark_as_read(
            user_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            last_read_message_id=uuid.uuid4(),
        )

        # Assert
        assert result == 0
        member_repo.update.assert_awaited_once()
        redis_mock.delete.assert_awaited_once()

    async def test_not_member(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.mark_as_read(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
            )


# ---------------------------------------------------------------------------
# _increment_unread_for_others (косвенно через send_message)
# ---------------------------------------------------------------------------
class TestUnreadIncrement:
    async def test_increments_for_non_sender_members(
        self,
        service,
        member_repo,
        conversation_repo,
        message_repo,
        redis_mock,
    ):
        # Arrange
        sender = uuid.uuid4()
        other1 = uuid.uuid4()
        other2 = uuid.uuid4()
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = SimpleNamespace(type="group")
        message_repo.get_by_client_message_id.return_value = None
        message_repo.create.return_value = _make_msg(sender_user_id=sender)
        member_repo.get_active_members.return_value = [
            SimpleNamespace(user_id=sender),
            SimpleNamespace(user_id=other1),
            SimpleNamespace(user_id=other2),
        ]

        # Act
        await service.send_message(
            sender_user_id=sender,
            sender_device_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            mls_ciphertext=b"ct",
            type="text",
            client_message_id=uuid.uuid4(),
        )

        # Assert — incr вызвали ровно для двух "others".
        assert redis_mock.incr.await_count == 2
