"""Unit tests for `app.services.user_service.UserService`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают сессию
SQLAlchemy, Redis-debounce и gRPC ContactsGateway, чтобы проверять именно
бизнес-логику сервиса без внешних зависимостей.
"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.user_service import UserService


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_execute_result(
    *,
    scalar_one_or_none=None,
    scalars_all=None,
    one_or_none=None,
) -> MagicMock:
    """Фабрика объекта, имитирующего результат `session.execute(...)`."""
    result = MagicMock()
    result.scalar_one_or_none = MagicMock(return_value=scalar_one_or_none)
    scalars = MagicMock()
    scalars.all = MagicMock(return_value=scalars_all or [])
    result.scalars = MagicMock(return_value=scalars)
    result.one_or_none = MagicMock(return_value=one_or_none)
    return result


def _queue_execute(session: MagicMock, results: list[MagicMock]) -> None:
    """Настраивает `session.execute` на последовательный возврат results."""
    session.execute = AsyncMock(side_effect=results)


def _make_service(
    *,
    contacts_is_blocked: bool = False,
) -> UserService:
    """Собирает UserService c замоканной сессией и ContactsGateway."""
    session = MagicMock()
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.execute = AsyncMock()
    session.delete = AsyncMock()

    contacts = MagicMock()
    contacts.is_blocked_either_way = AsyncMock(return_value=contacts_is_blocked)

    return UserService(session=session, contacts_gateway=contacts)


@pytest.fixture
def service() -> UserService:
    return _make_service()


@pytest.fixture
def debounce_mock():
    """Мокает `check_and_set_last_active_debounce` в модуле user_service."""
    with patch(
        "app.services.user_service.check_and_set_last_active_debounce",
        new=AsyncMock(return_value=True),
    ) as m:
        yield m


# ---------------------------------------------------------------------------
# create_user
# ---------------------------------------------------------------------------
class TestCreateUser:
    async def test_create_user_persists_user_and_settings(self, service):
        # Arrange
        user_id = str(uuid.uuid4())

        # Act
        user = await service.create_user(user_id=user_id, username="alice")

        # Assert
        assert str(user.id) == user_id
        assert user.username == "alice"
        # user_public_key — base64 от 32 байт => 44 символа с паддингом
        decoded = base64.b64decode(user.user_public_key)
        assert len(decoded) == 32
        assert service.session.add.call_count == 2  # user + settings
        assert service.session.flush.await_count == 2


# ---------------------------------------------------------------------------
# get_user
# ---------------------------------------------------------------------------
class TestGetUser:
    async def test_get_user_owner_returns_visible_true(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        user = SimpleNamespace(id=uuid.UUID(user_id), is_deleted=False)
        settings = SimpleNamespace(profile_visible_to="nobody")
        _queue_execute(
            service.session,
            [
                _make_execute_result(scalar_one_or_none=user),
                _make_execute_result(scalar_one_or_none=settings),
            ],
        )

        # Act
        result_user, visible = await service.get_user(user_id, user_id)

        # Assert
        assert result_user is user
        assert visible is True
        service._contacts.is_blocked_either_way.assert_not_called()

    async def test_get_user_other_with_nobody_visibility_returns_false(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        requester = str(uuid.uuid4())
        user = SimpleNamespace(id=uuid.UUID(user_id), is_deleted=False)
        settings = SimpleNamespace(profile_visible_to="nobody")
        _queue_execute(
            service.session,
            [
                _make_execute_result(scalar_one_or_none=user),
                _make_execute_result(scalar_one_or_none=settings),
            ],
        )

        # Act
        result_user, visible = await service.get_user(user_id, requester)

        # Assert
        assert result_user is user
        assert visible is False

    async def test_get_user_other_with_everybody_visibility_returns_true(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        requester = str(uuid.uuid4())
        user = SimpleNamespace(id=uuid.UUID(user_id), is_deleted=False)
        settings = SimpleNamespace(profile_visible_to="everybody")
        _queue_execute(
            service.session,
            [
                _make_execute_result(scalar_one_or_none=user),
                _make_execute_result(scalar_one_or_none=settings),
            ],
        )

        # Act
        _, visible = await service.get_user(user_id, requester)

        # Assert
        assert visible is True

    async def test_get_user_blocked_either_way_raises(self, service):
        # Arrange
        service._contacts.is_blocked_either_way = AsyncMock(return_value=True)
        user_id = str(uuid.uuid4())
        requester = str(uuid.uuid4())
        user = SimpleNamespace(id=uuid.UUID(user_id), is_deleted=False)
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user(user_id, requester)

    async def test_get_user_not_found_raises(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user(user_id, user_id)

    async def test_get_user_deleted_raises(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        user = SimpleNamespace(id=uuid.UUID(user_id), is_deleted=True)
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user(user_id, user_id)


# ---------------------------------------------------------------------------
# get_user_by_public_key
# ---------------------------------------------------------------------------
class TestGetUserByPublicKey:
    async def test_owner_returns_visible_true_even_with_nobody(self, service):
        # Arrange
        user_uuid = uuid.uuid4()
        requester = str(user_uuid)
        user = SimpleNamespace(id=user_uuid, is_deleted=False)
        settings = SimpleNamespace(profile_visible_to="nobody")
        _queue_execute(
            service.session,
            [
                _make_execute_result(scalar_one_or_none=user),
                _make_execute_result(scalar_one_or_none=settings),
            ],
        )

        # Act
        result_user, visible = await service.get_user_by_public_key("pk", requester)

        # Assert
        assert result_user is user
        assert visible is True

    async def test_other_with_nobody_visibility_returns_false(self, service):
        # Arrange
        user = SimpleNamespace(id=uuid.uuid4(), is_deleted=False)
        settings = SimpleNamespace(profile_visible_to="nobody")
        _queue_execute(
            service.session,
            [
                _make_execute_result(scalar_one_or_none=user),
                _make_execute_result(scalar_one_or_none=settings),
            ],
        )

        # Act
        _, visible = await service.get_user_by_public_key("pk", str(uuid.uuid4()))

        # Assert
        assert visible is False

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user_by_public_key("pk", str(uuid.uuid4()))

    async def test_blocked_either_way_raises(self, service):
        # Arrange
        service._contacts.is_blocked_either_way = AsyncMock(return_value=True)
        user = SimpleNamespace(id=uuid.uuid4(), is_deleted=False)
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user_by_public_key("pk", str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# get_user_with_settings
# ---------------------------------------------------------------------------
class TestGetUserWithSettings:
    async def test_returns_user_and_settings(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        settings = SimpleNamespace(theme="dark")
        user = SimpleNamespace(id=uuid.UUID(user_id), is_deleted=False, settings=settings)
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act
        result_user, result_settings = await service.get_user_with_settings(user_id)

        # Assert
        assert result_user is user
        assert result_settings is settings

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user_with_settings(str(uuid.uuid4()))

    async def test_deleted_raises(self, service):
        # Soft-deleted users are RETURNED so peers can render a tombstone
        # profile (privacy filtering happens in the gRPC handler layer).
        user = SimpleNamespace(id=uuid.uuid4(), is_deleted=True, settings=None)
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        returned, settings = await service.get_user_with_settings(str(user.id))
        assert returned is user
        assert settings is None


# ---------------------------------------------------------------------------
# get_user_by_public_key_with_settings
# ---------------------------------------------------------------------------
class TestGetUserByPublicKeyWithSettings:
    async def test_returns_user_and_settings(self, service):
        # Arrange
        settings = SimpleNamespace(theme="light")
        user = SimpleNamespace(id=uuid.uuid4(), settings=settings)
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act
        result_user, result_settings = await service.get_user_by_public_key_with_settings("pk")

        # Assert
        assert result_user is user
        assert result_settings is settings

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.get_user_by_public_key_with_settings("pk")


# ---------------------------------------------------------------------------
# update_user
# ---------------------------------------------------------------------------
class TestUpdateUser:
    async def test_updates_all_optional_fields(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        avatar = str(uuid.uuid4())
        bg = str(uuid.uuid4())
        user = SimpleNamespace(
            id=uuid.UUID(user_id),
            is_deleted=False,
            username="old",
            bio="old",
            avatar_media_id=None,
            profile_background_media_id=None,
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act
        result = await service.update_user(
            user_id=user_id,
            bio="new bio",
            username="new_name",
            avatar_media_id=avatar,
            profile_background_media_id=bg,
        )

        # Assert
        assert result.username == "new_name"
        assert result.bio == "new bio"
        assert result.avatar_media_id == uuid.UUID(avatar)
        assert result.profile_background_media_id == uuid.UUID(bg)
        service.session.flush.assert_awaited()

    async def test_empty_avatar_clears_field(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        user = SimpleNamespace(
            id=uuid.UUID(user_id),
            is_deleted=False,
            username="u",
            bio=None,
            avatar_media_id=uuid.uuid4(),
            profile_background_media_id=uuid.uuid4(),
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act
        result = await service.update_user(
            user_id=user_id,
            avatar_media_id="",
            profile_background_media_id="",
        )

        # Assert
        assert result.avatar_media_id is None
        assert result.profile_background_media_id is None

    async def test_no_fields_still_flushes_without_changes(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        user = SimpleNamespace(
            id=uuid.UUID(user_id),
            is_deleted=False,
            username="keep",
            bio="keep",
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act
        result = await service.update_user(user_id=user_id)

        # Assert
        assert result.username == "keep"
        assert result.bio == "keep"
        service.session.flush.assert_awaited()

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.update_user(user_id=str(uuid.uuid4()), bio="x")


# ---------------------------------------------------------------------------
# delete_user
# ---------------------------------------------------------------------------
class TestDeleteUser:
    async def test_marks_user_deleted_and_suffixes_username(self, service):
        # Arrange
        user_id = str(uuid.uuid4())
        user = SimpleNamespace(
            id=uuid.UUID(user_id),
            is_deleted=False,
            username="bob",
            bio="hi",
            avatar_media_id=uuid.uuid4(),
            profile_background_media_id=None,
            user_public_key="pk",
            last_active=datetime.utcnow(),
            deleted_at=None,
            settings=None,
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        # Act
        deleted_at, media_ids = await service.delete_user(user_id)

        # Assert: soft-delete with PII wipe + anonymized username.
        assert isinstance(deleted_at, datetime)
        assert isinstance(media_ids, list)
        assert user.is_deleted is True
        assert user.deleted_at == deleted_at
        assert user.username.startswith("deleted_")
        assert user.username != "bob"
        assert user.bio is None
        assert user.avatar_media_id is None
        assert user.user_public_key is None
        assert user.last_active is None
        # Row is NOT physically deleted — peers must keep resolving the id.
        service.session.delete.assert_not_called()
        service.session.flush.assert_awaited()

    async def test_idempotent_when_already_deleted(self, service):
        user_id = str(uuid.uuid4())
        user = SimpleNamespace(
            id=uuid.UUID(user_id),
            is_deleted=True,
            username="deleted_xxx",
            bio=None,
            avatar_media_id=None,
            profile_background_media_id=None,
            user_public_key=None,
            last_active=None,
            deleted_at=datetime.utcnow(),
            settings=None,
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=user)])

        deleted_at, media_ids = await service.delete_user(user_id)

        assert deleted_at == user.deleted_at
        assert media_ids == []
        service.session.delete.assert_not_called()

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="User not found"):
            await service.delete_user(str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# check_and_process_auto_delete
# ---------------------------------------------------------------------------
class TestCheckAndProcessAutoDelete:
    """`check_and_process_auto_delete` строит SQL через `func.make_interval`,

    который в новых версиях SQLAlchemy не принимает kwargs в конструкторе
    `Function`. Для unit-теста сам SQL нас не интересует — важна логика
    обработки результата, поэтому патчим `sqlalchemy.sql.func.make_interval`.
    """

    async def test_deletes_expired_users(self, service):
        # Arrange
        u1 = SimpleNamespace(id=uuid.uuid4(), is_deleted=False, username="a", deleted_at=None)
        u2 = SimpleNamespace(id=uuid.uuid4(), is_deleted=False, username="b", deleted_at=None)
        # 1-й execute — отбор кандидатов; затем для каждого delete_user идёт
        # свой SELECT по пользователю.
        service.session.execute = AsyncMock(
            side_effect=[
                _make_execute_result(scalars_all=[u1, u2]),
                _make_execute_result(scalar_one_or_none=u1),
                _make_execute_result(scalar_one_or_none=u2),
            ]
        )

        # Act
        with patch("sqlalchemy.sql.func.make_interval", return_value=MagicMock()):
            count, ids = await service.check_and_process_auto_delete()

        # Assert
        assert count == 2
        assert ids == [str(u1.id), str(u2.id)]
        # Soft-delete: каждый кандидат помечен is_deleted=True и анонимизирован.
        for u in (u1, u2):
            assert u.is_deleted is True
            assert u.deleted_at is not None
            assert u.username.startswith("deleted_")

    async def test_no_candidates_returns_zero(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalars_all=[])])

        # Act
        with patch("sqlalchemy.sql.func.make_interval", return_value=MagicMock()):
            count, ids = await service.check_and_process_auto_delete()

        # Assert
        assert count == 0
        assert ids == []


# ---------------------------------------------------------------------------
# get_user_settings
# ---------------------------------------------------------------------------
class TestGetUserSettings:
    async def test_returns_settings(self, service):
        # Arrange
        settings = SimpleNamespace(theme="dark")
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=settings)])

        # Act
        result = await service.get_user_settings(str(uuid.uuid4()))

        # Assert
        assert result is settings

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="Settings not found"):
            await service.get_user_settings(str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# update_user_settings
# ---------------------------------------------------------------------------
class TestUpdateUserSettings:
    async def test_updates_allowed_fields_and_ignores_unknown(self, service):
        # Arrange
        settings = SimpleNamespace(
            theme="system",
            language="en",
            is_translator_active=False,
            updated_at=None,
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=settings)])

        # Act
        result = await service.update_user_settings(
            str(uuid.uuid4()),
            theme="dark",
            language="ru",
            is_translator_active=True,
            not_allowed_field="should be ignored",
        )

        # Assert
        assert result.theme == "dark"
        assert result.language == "ru"
        assert result.is_translator_active is True
        assert not hasattr(result, "not_allowed_field")
        assert isinstance(result.updated_at, datetime)

    async def test_converts_uuid_fields_from_string(self, service):
        # Arrange
        settings = SimpleNamespace(
            chat_background_media_id=None,
            top_bar_media_id=None,
            my_bubble_media_id=None,
            their_bubble_media_id=None,
            updated_at=None,
        )
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=settings)])
        bg = str(uuid.uuid4())

        # Act
        result = await service.update_user_settings(
            str(uuid.uuid4()),
            chat_background_media_id=bg,
            top_bar_media_id="",
        )

        # Assert
        assert result.chat_background_media_id == uuid.UUID(bg)
        assert result.top_bar_media_id is None

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="Settings not found"):
            await service.update_user_settings(str(uuid.uuid4()), theme="dark")


# ---------------------------------------------------------------------------
# get_users_batch
# ---------------------------------------------------------------------------
class TestGetUsersBatch:
    async def test_returns_all_users(self, service):
        # Arrange
        u1 = SimpleNamespace(id=uuid.uuid4())
        u2 = SimpleNamespace(id=uuid.uuid4())
        _queue_execute(service.session, [_make_execute_result(scalars_all=[u1, u2])])

        # Act
        result = await service.get_users_batch([str(u1.id), str(u2.id)])

        # Assert
        assert result == [u1, u2]

    async def test_empty_batch_returns_empty_list(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalars_all=[])])

        # Act
        result = await service.get_users_batch([])

        # Assert
        assert result == []


# ---------------------------------------------------------------------------
# user_exists
# ---------------------------------------------------------------------------
class TestUserExists:
    async def test_unknown_user_returns_false_false(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(one_or_none=None)])

        # Act
        exists, deleted = await service.user_exists(str(uuid.uuid4()))

        # Assert
        assert exists is False
        assert deleted is False

    @pytest.mark.parametrize("is_deleted", [False, True])
    async def test_returns_existence_flag_and_deleted_flag(self, service, is_deleted):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(one_or_none=(is_deleted,))])

        # Act
        exists, deleted = await service.user_exists(str(uuid.uuid4()))

        # Assert
        assert exists is True
        assert deleted is is_deleted


# ---------------------------------------------------------------------------
# update_last_active
# ---------------------------------------------------------------------------
class TestUpdateLastActive:
    async def test_skips_update_when_debounced(self, service):
        # Arrange
        with patch(
            "app.services.user_service.check_and_set_last_active_debounce",
            new=AsyncMock(return_value=False),
        ):
            # Act
            result = await service.update_last_active(str(uuid.uuid4()))

        # Assert
        assert result is True
        service.session.execute.assert_not_called()
        service.session.flush.assert_not_called()

    async def test_runs_update_when_not_debounced(self, service):
        # Arrange
        service.session.execute = AsyncMock(return_value=MagicMock())
        with patch(
            "app.services.user_service.check_and_set_last_active_debounce",
            new=AsyncMock(return_value=True),
        ):
            # Act
            result = await service.update_last_active(str(uuid.uuid4()))

        # Assert
        assert result is True
        service.session.execute.assert_awaited_once()
        service.session.flush.assert_awaited_once()


# ---------------------------------------------------------------------------
# get_privacy_settings
# ---------------------------------------------------------------------------
class TestGetPrivacySettings:
    async def test_returns_settings(self, service):
        # Arrange
        settings = SimpleNamespace(profile_visible_to="contacts")
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=settings)])

        # Act
        result = await service.get_privacy_settings(str(uuid.uuid4()))

        # Assert
        assert result is settings

    async def test_not_found_raises(self, service):
        # Arrange
        _queue_execute(service.session, [_make_execute_result(scalar_one_or_none=None)])

        # Act / Assert
        with pytest.raises(ValueError, match="Settings not found"):
            await service.get_privacy_settings(str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# Constructor default gateway
# ---------------------------------------------------------------------------
class TestConstructor:
    def test_uses_default_contacts_gateway_when_not_provided(self):
        # Arrange
        session = MagicMock()

        # Act
        with patch("app.services.user_service.ContactsGateway") as gw_cls:
            gw_cls.return_value = MagicMock(name="default_gw")
            svc = UserService(session=session)

        # Assert
        assert svc.session is session
        gw_cls.assert_called_once_with()
