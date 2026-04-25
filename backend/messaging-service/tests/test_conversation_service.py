"""Unit tests for `app.services.conversation_service.ConversationServiceImpl`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают репозитории,
Redis, IContactsClient и IStreamService, чтобы проверить именно бизнес-логику
управления диалогами и группами.
"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services.conversation_service import ConversationServiceImpl
from app.services.interfaces.stream_service import IStreamService


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _repo(*names: str) -> MagicMock:
    r = MagicMock()
    for n in names:
        setattr(r, n, AsyncMock())
    return r


def _make_conv(**overrides) -> SimpleNamespace:
    base = dict(
        id=uuid.uuid4(),
        type="direct",
        name=None,
        created_at=datetime.utcnow(),
        last_activity_at=datetime.utcnow(),
        avatar_media_id=None,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def _make_member(**overrides) -> SimpleNamespace:
    base = dict(
        user_id=uuid.uuid4(),
        role="member",
        joined_at=datetime.utcnow(),
        left_at=None,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


@pytest.fixture
def conversation_repo() -> MagicMock:
    repo = _repo(
        "find_direct_between",
        "create",
        "get_by_id",
        "update_avatar",
        "update_name",
        "get_user_conversations",
    )
    # session для delete_conversation:
    session = MagicMock()
    session.execute = AsyncMock()
    session.flush = AsyncMock()
    repo.session = session
    return repo


@pytest.fixture
def member_repo() -> MagicMock:
    return _repo(
        "get_active_members",
        "create",
        "update",
        "is_member",
        "get_active_member",
        "update_role",
    )


@pytest.fixture
def mls_group_repo() -> MagicMock:
    return _repo("get_by_conversation_id", "create")


@pytest.fixture
def welcome_repo() -> MagicMock:
    return _repo("create")


@pytest.fixture
def commit_repo() -> MagicMock:
    return _repo()


@pytest.fixture
def message_repo() -> MagicMock:
    return _repo("get_last_message")


@pytest.fixture
def contacts_client() -> MagicMock:
    client = MagicMock()
    client.is_blocked = AsyncMock(return_value=False)
    return client


@pytest.fixture
def redis_mock() -> MagicMock:
    redis = MagicMock()
    redis.get = AsyncMock(return_value=None)
    return redis


@pytest.fixture
def stream_mock() -> MagicMock:
    stream = MagicMock(spec=IStreamService)
    stream.publish_event = AsyncMock()
    return stream


@pytest.fixture
def service(
    conversation_repo,
    member_repo,
    mls_group_repo,
    welcome_repo,
    commit_repo,
    message_repo,
    contacts_client,
    redis_mock,
    stream_mock,
) -> ConversationServiceImpl:
    return ConversationServiceImpl(
        conversation_repo,
        member_repo,
        mls_group_repo,
        welcome_repo,
        commit_repo,
        message_repo,
        contacts_client,
        redis_mock,
        stream_mock,
    )


# ---------------------------------------------------------------------------
# create_direct
# ---------------------------------------------------------------------------
class TestCreateDirect:
    async def test_happy_path_creates_new_conversation(
        self,
        service,
        conversation_repo,
        member_repo,
        mls_group_repo,
        welcome_repo,
    ):
        # Arrange
        initiator = uuid.uuid4()
        recipient = uuid.uuid4()
        device = uuid.uuid4()
        conversation_repo.find_direct_between.return_value = None
        conv = _make_conv(type="direct")
        conversation_repo.create.return_value = conv
        member_repo.create.side_effect = [
            _make_member(user_id=initiator, role="owner"),
            _make_member(user_id=recipient, role="member"),
        ]

        # Act
        result = await service.create_direct(
            initiator,
            device,
            recipient,
            welcome_messages=[(uuid.uuid4(), b"w1")],
        )

        # Assert
        assert result.id == conv.id
        assert len(result.members) == 2
        assert result.mls_group.current_epoch == 1
        mls_group_repo.create.assert_awaited_once()
        welcome_repo.create.assert_awaited_once()

    async def test_idempotent_if_exists(
        self,
        service,
        conversation_repo,
        member_repo,
        mls_group_repo,
    ):
        # Arrange
        existing = _make_conv(type="direct")
        conversation_repo.find_direct_between.return_value = existing
        member_repo.get_active_members.return_value = [_make_member()]
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=7,
            cipher_suite=1,
        )

        # Act
        result = await service.create_direct(
            uuid.uuid4(),
            uuid.uuid4(),
            uuid.uuid4(),
            welcome_messages=[],
        )

        # Assert
        assert result.id == existing.id
        assert result.mls_group.current_epoch == 7
        conversation_repo.create.assert_not_awaited()

    async def test_rejects_blocked(self, service, contacts_client):
        # Arrange
        contacts_client.is_blocked.return_value = True

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.create_direct(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
                welcome_messages=[],
            )


# ---------------------------------------------------------------------------
# create_group
# ---------------------------------------------------------------------------
class TestCreateGroup:
    async def test_happy_path(
        self,
        service,
        conversation_repo,
        member_repo,
        mls_group_repo,
        welcome_repo,
    ):
        # Arrange
        creator = uuid.uuid4()
        m1 = uuid.uuid4()
        conv = _make_conv(type="group", name="Team")
        conversation_repo.create.return_value = conv
        member_repo.create.side_effect = [
            _make_member(user_id=creator, role="owner"),
            _make_member(user_id=m1, role="member"),
        ]

        # Act
        result = await service.create_group(
            creator_user_id=creator,
            creator_device_id=uuid.uuid4(),
            name="Team",
            members=[(m1, [(uuid.uuid4(), b"w")])],
        )

        # Assert
        assert result.type == "group"
        assert len(result.members) == 2
        mls_group_repo.create.assert_awaited_once()
        welcome_repo.create.assert_awaited_once()

    async def test_rejects_when_any_member_blocked(
        self,
        service,
        contacts_client,
    ):
        # Arrange
        contacts_client.is_blocked.return_value = True

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.create_group(
                creator_user_id=uuid.uuid4(),
                creator_device_id=uuid.uuid4(),
                name="T",
                members=[(uuid.uuid4(), [])],
            )


# ---------------------------------------------------------------------------
# get_conversations
# ---------------------------------------------------------------------------
class TestGetConversations:
    async def test_happy_path_with_pagination(
        self,
        service,
        conversation_repo,
        message_repo,
        redis_mock,
    ):
        # Arrange
        convs = [_make_conv() for _ in range(3)]
        # limit=2, вернули limit+1=3 → has_next=True
        conversation_repo.get_user_conversations.return_value = convs
        message_repo.get_last_message.return_value = SimpleNamespace(type="text")
        redis_mock.get.return_value = b"5"

        # Act
        result = await service.get_conversations(uuid.uuid4(), limit=2, cursor=None)

        # Assert
        assert len(result.items) == 2
        assert result.next_cursor is not None
        assert result.items[0].unread_count == 5

    async def test_no_next_cursor_when_done(
        self,
        service,
        conversation_repo,
        message_repo,
    ):
        # Arrange
        conversation_repo.get_user_conversations.return_value = [_make_conv()]
        message_repo.get_last_message.return_value = None

        # Act
        result = await service.get_conversations(uuid.uuid4(), limit=5, cursor=None)

        # Assert
        assert result.next_cursor is None
        assert result.items[0].last_message_type is None


# ---------------------------------------------------------------------------
# get_conversation
# ---------------------------------------------------------------------------
class TestGetConversation:
    async def test_happy_path(
        self,
        service,
        conversation_repo,
        member_repo,
        mls_group_repo,
    ):
        # Arrange
        conv = _make_conv(type="group", name="N")
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = conv
        member_repo.get_active_members.return_value = [_make_member()]
        mls_group_repo.get_by_conversation_id.return_value = SimpleNamespace(
            current_epoch=3,
            cipher_suite=1,
        )

        # Act
        result = await service.get_conversation(uuid.uuid4(), conv.id)

        # Assert
        assert result.id == conv.id
        assert result.mls_group.current_epoch == 3

    async def test_not_member(self, service, member_repo):
        # Arrange
        member_repo.is_member.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="Not a member"):
            await service.get_conversation(uuid.uuid4(), uuid.uuid4())

    async def test_missing_conversation(
        self,
        service,
        member_repo,
        conversation_repo,
    ):
        # Arrange
        member_repo.is_member.return_value = True
        conversation_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Conversation not found"):
            await service.get_conversation(uuid.uuid4(), uuid.uuid4())


# ---------------------------------------------------------------------------
# leave_conversation
# ---------------------------------------------------------------------------
class TestLeaveConversation:
    async def test_happy_path(self, service, member_repo, stream_mock):
        # Arrange
        member_repo.get_active_member.return_value = _make_member()

        # Act
        ok = await service.leave_conversation(
            uuid.uuid4(),
            uuid.uuid4(),
            uuid.uuid4(),
            b"commit",
        )

        # Assert
        assert ok is True
        member_repo.update.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()
        assert stream_mock.publish_event.await_args.args[1]["event_type"] == "member_left"

    async def test_not_member(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Not a member"):
            await service.leave_conversation(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
                b"",
            )


# ---------------------------------------------------------------------------
# kick_member
# ---------------------------------------------------------------------------
class TestKickMember:
    async def test_happy_path_owner_kicks_member(
        self,
        service,
        member_repo,
        stream_mock,
    ):
        # Arrange
        caller_id = uuid.uuid4()
        target_id = uuid.uuid4()
        member_repo.get_active_member.side_effect = [
            _make_member(user_id=caller_id, role="owner"),
            _make_member(user_id=target_id, role="member"),
        ]

        # Act
        ok = await service.kick_member(caller_id, uuid.uuid4(), target_id)

        # Assert
        assert ok is True
        member_repo.update.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()

    async def test_cannot_kick_self(self, service, member_repo):
        # Arrange
        user = uuid.uuid4()
        member_repo.get_active_member.return_value = _make_member(
            user_id=user,
            role="owner",
        )

        # Act / Assert
        with pytest.raises(ValueError, match="Cannot kick yourself"):
            await service.kick_member(user, uuid.uuid4(), user)

    async def test_regular_member_cannot_kick(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.return_value = _make_member(role="member")

        # Act / Assert
        with pytest.raises(ValueError, match="Only admins can kick"):
            await service.kick_member(uuid.uuid4(), uuid.uuid4(), uuid.uuid4())

    async def test_cannot_kick_owner(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.side_effect = [
            _make_member(role="admin"),
            _make_member(role="owner"),
        ]

        # Act / Assert
        with pytest.raises(ValueError, match="Cannot kick the group owner"):
            await service.kick_member(uuid.uuid4(), uuid.uuid4(), uuid.uuid4())

    async def test_admin_cannot_kick_admin(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.side_effect = [
            _make_member(role="admin"),
            _make_member(role="admin"),
        ]

        # Act / Assert
        with pytest.raises(ValueError, match="Only the owner can kick admins"):
            await service.kick_member(uuid.uuid4(), uuid.uuid4(), uuid.uuid4())

    async def test_caller_not_member(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Not a member"):
            await service.kick_member(uuid.uuid4(), uuid.uuid4(), uuid.uuid4())


# ---------------------------------------------------------------------------
# update_member_role
# ---------------------------------------------------------------------------
class TestUpdateMemberRole:
    async def test_happy_path(self, service, member_repo, stream_mock):
        # Arrange
        caller_id = uuid.uuid4()
        target_id = uuid.uuid4()
        member_repo.get_active_member.side_effect = [
            _make_member(user_id=caller_id, role="owner"),
            _make_member(user_id=target_id, role="member"),
        ]

        # Act
        ok = await service.update_member_role(
            caller_id,
            uuid.uuid4(),
            target_id,
            "admin",
        )

        # Assert
        assert ok is True
        member_repo.update_role.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()

    async def test_invalid_role(self, service):
        # Act / Assert
        with pytest.raises(ValueError, match="must be 'admin' or 'member'"):
            await service.update_member_role(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
                "super-admin",
            )

    async def test_cannot_change_own_role(self, service, member_repo):
        # Arrange
        user = uuid.uuid4()
        member_repo.get_active_member.return_value = _make_member(
            user_id=user,
            role="owner",
        )

        # Act / Assert
        with pytest.raises(ValueError, match="Cannot change your own role"):
            await service.update_member_role(
                user,
                uuid.uuid4(),
                user,
                "admin",
            )

    async def test_noop_if_already_same_role(
        self,
        service,
        member_repo,
        stream_mock,
    ):
        # Arrange
        caller_id = uuid.uuid4()
        target_id = uuid.uuid4()
        member_repo.get_active_member.side_effect = [
            _make_member(user_id=caller_id, role="owner"),
            _make_member(user_id=target_id, role="admin"),
        ]

        # Act
        ok = await service.update_member_role(
            caller_id,
            uuid.uuid4(),
            target_id,
            "admin",
        )

        # Assert
        assert ok is True
        member_repo.update_role.assert_not_awaited()
        stream_mock.publish_event.assert_not_awaited()

    async def test_admin_cannot_demote_admin(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.side_effect = [
            _make_member(role="admin"),
            _make_member(role="admin"),
        ]

        # Act / Assert
        with pytest.raises(ValueError, match="Only the owner can demote"):
            await service.update_member_role(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
                "member",
            )

    async def test_cannot_change_owner_role(self, service, member_repo):
        # Arrange
        member_repo.get_active_member.side_effect = [
            _make_member(role="owner"),
            _make_member(role="owner"),
        ]

        # Act / Assert
        with pytest.raises(ValueError, match="Cannot change the owner's role"):
            await service.update_member_role(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
                "admin",
            )


# ---------------------------------------------------------------------------
# update_group_avatar
# ---------------------------------------------------------------------------
class TestUpdateGroupAvatar:
    async def test_happy_path(
        self,
        service,
        conversation_repo,
        member_repo,
        stream_mock,
    ):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="group")
        member_repo.get_active_member.return_value = _make_member(role="owner")

        # Act
        ok = await service.update_group_avatar(
            uuid.uuid4(),
            uuid.uuid4(),
            uuid.uuid4(),
        )

        # Assert
        assert ok is True
        conversation_repo.update_avatar.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()

    async def test_not_group(self, service, conversation_repo):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="direct")

        # Act / Assert
        with pytest.raises(ValueError, match="only supported for group"):
            await service.update_group_avatar(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
            )

    async def test_not_found(self, service, conversation_repo):
        # Arrange
        conversation_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Conversation not found"):
            await service.update_group_avatar(
                uuid.uuid4(),
                uuid.uuid4(),
                None,
            )

    async def test_non_admin_rejected(
        self,
        service,
        conversation_repo,
        member_repo,
    ):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="group")
        member_repo.get_active_member.return_value = _make_member(role="member")

        # Act / Assert
        with pytest.raises(ValueError, match="Only owner or admin"):
            await service.update_group_avatar(
                uuid.uuid4(),
                uuid.uuid4(),
                uuid.uuid4(),
            )


# ---------------------------------------------------------------------------
# update_group_name
# ---------------------------------------------------------------------------
class TestUpdateGroupName:
    async def test_happy_path(
        self,
        service,
        conversation_repo,
        member_repo,
        stream_mock,
    ):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="group")
        member_repo.get_active_member.return_value = _make_member(role="admin")

        # Act
        ok = await service.update_group_name(
            uuid.uuid4(),
            uuid.uuid4(),
            "  New Name  ",
        )

        # Assert
        assert ok is True
        conversation_repo.update_name.assert_awaited_once_with(
            conversation_repo.update_name.await_args.args[0],
            "New Name",
        )
        stream_mock.publish_event.assert_awaited_once()

    async def test_empty_name_rejected(self, service):
        # Act / Assert
        with pytest.raises(ValueError, match="must not be empty"):
            await service.update_group_name(
                uuid.uuid4(),
                uuid.uuid4(),
                "   ",
            )

    async def test_not_group(self, service, conversation_repo):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="direct")

        # Act / Assert
        with pytest.raises(ValueError, match="only be changed for group"):
            await service.update_group_name(uuid.uuid4(), uuid.uuid4(), "x")

    async def test_not_found(self, service, conversation_repo):
        # Arrange
        conversation_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Conversation not found"):
            await service.update_group_name(uuid.uuid4(), uuid.uuid4(), "x")


# ---------------------------------------------------------------------------
# delete_conversation
# ---------------------------------------------------------------------------
class TestDeleteConversation:
    async def test_direct_any_member_can_delete(
        self,
        service,
        conversation_repo,
        member_repo,
        stream_mock,
    ):
        # Arrange
        conv = _make_conv(type="direct")
        conversation_repo.get_by_id.return_value = conv
        member_repo.get_active_member.return_value = _make_member(role="member")

        # Act
        ok = await service.delete_conversation(uuid.uuid4(), conv.id)

        # Assert
        assert ok is True
        # 6 executes + 1 flush
        assert conversation_repo.session.execute.await_count == 6
        conversation_repo.session.flush.assert_awaited_once()
        stream_mock.publish_event.assert_awaited_once()

    async def test_group_only_owner_can_delete(
        self,
        service,
        conversation_repo,
        member_repo,
    ):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="group")
        member_repo.get_active_member.return_value = _make_member(role="admin")

        # Act / Assert
        with pytest.raises(ValueError, match="Only the group owner"):
            await service.delete_conversation(uuid.uuid4(), uuid.uuid4())

    async def test_not_found(self, service, conversation_repo):
        # Arrange
        conversation_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Conversation not found"):
            await service.delete_conversation(uuid.uuid4(), uuid.uuid4())

    async def test_caller_not_member(
        self,
        service,
        conversation_repo,
        member_repo,
    ):
        # Arrange
        conversation_repo.get_by_id.return_value = _make_conv(type="direct")
        member_repo.get_active_member.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="Not a member"):
            await service.delete_conversation(uuid.uuid4(), uuid.uuid4())


# ---------------------------------------------------------------------------
# purge_user_membership
# ---------------------------------------------------------------------------
class TestPurgeUserMembership:
    async def test_groups_and_directs_marked_left_and_events_published(
        self,
        service,
        conversation_repo,
        stream_mock,
    ):
        # Arrange
        user_id = uuid.uuid4()
        group_conv_a = _make_conv(type="group")
        group_conv_b = _make_conv(type="group")
        direct_conv = _make_conv(type="direct")
        group_member_a = _make_member(user_id=user_id, role="member")
        group_member_a.id = uuid.uuid4()
        group_member_b = _make_member(user_id=user_id, role="owner")
        group_member_b.id = uuid.uuid4()
        direct_member = _make_member(user_id=user_id, role="member")
        direct_member.id = uuid.uuid4()

        select_result = MagicMock()
        select_result.all.return_value = [
            (group_member_a, group_conv_a),
            (group_member_b, group_conv_b),
            (direct_member, direct_conv),
        ]
        conversation_repo.session.execute.return_value = select_result

        # Act
        groups_left, directs_left = await service.purge_user_membership(user_id)

        # Assert
        assert groups_left == 2
        assert directs_left == 1
        # All memberships are now soft-left (no hard delete for directs).
        assert group_member_a.left_at is not None
        assert group_member_b.left_at is not None
        assert direct_member.left_at is not None
        # Only one SELECT — no DELETE statement.
        assert conversation_repo.session.execute.await_count == 1
        conversation_repo.session.flush.assert_awaited_once()
        # 3 conversations × 1 member_left event each.
        assert stream_mock.publish_event.await_count == 3
        for call in stream_mock.publish_event.await_args_list:
            payload = call.args[1]
            assert payload["event_type"] == "member_left"
            assert payload["reason"] == "account_deleted"

    async def test_idempotent_when_no_memberships(
        self,
        service,
        conversation_repo,
        stream_mock,
    ):
        # Arrange
        select_result = MagicMock()
        select_result.all.return_value = []
        conversation_repo.session.execute.return_value = select_result

        # Act
        groups_left, directs_left = await service.purge_user_membership(uuid.uuid4())

        # Assert
        assert groups_left == 0
        assert directs_left == 0
        # Only the SELECT is executed, no DELETE.
        assert conversation_repo.session.execute.await_count == 1
        conversation_repo.session.flush.assert_awaited_once()
        stream_mock.publish_event.assert_not_awaited()


# ---------------------------------------------------------------------------
# _encode_cursor / _decode_cursor (статические)
# ---------------------------------------------------------------------------
class TestCursor:
    def test_encode_decode_roundtrip(self):
        # Arrange
        ts = datetime(2025, 1, 2, 3, 4, 5)
        cid = uuid.uuid4()

        # Act
        encoded = ConversationServiceImpl._encode_cursor(ts, cid)
        ts2, cid2 = ConversationServiceImpl._decode_cursor(encoded)

        # Assert
        assert ts2 == ts
        assert cid2 == cid

    def test_decode_none_returns_none_pair(self):
        # Act
        ts, cid = ConversationServiceImpl._decode_cursor(None)

        # Assert
        assert ts is None
        assert cid is None

    def test_decode_garbage_returns_none_pair(self):
        # Arrange
        bad = base64.urlsafe_b64encode(b"not-a-cursor").decode()

        # Act
        ts, cid = ConversationServiceImpl._decode_cursor(bad)

        # Assert
        assert ts is None
        assert cid is None

    def test_decode_invalid_base64(self):
        # Act
        ts, cid = ConversationServiceImpl._decode_cursor("!!!not-base64!!!")

        # Assert
        assert ts is None
        assert cid is None


# ---------------------------------------------------------------------------
# _get_unread_count (косвенно)
# ---------------------------------------------------------------------------
class TestUnreadCountFallback:
    async def test_uses_zero_when_redis_empty(
        self,
        service,
        conversation_repo,
        message_repo,
        redis_mock,
    ):
        # Arrange
        conversation_repo.get_user_conversations.return_value = [_make_conv()]
        message_repo.get_last_message.return_value = None
        redis_mock.get.return_value = None

        # Act
        result = await service.get_conversations(
            uuid.uuid4(),
            limit=10,
            cursor=None,
        )

        # Assert
        assert result.items[0].unread_count == 0
