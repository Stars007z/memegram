"""Unit tests for `app.services.contacts_service.ContactsService`.

AAA, мокают ContactRepository/BlockedUserRepository и UserServiceClient.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services.contacts_service import ContactsService
from app.services.user_client import UserBriefProfile


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_service() -> ContactsService:
    session = MagicMock()
    user_client = MagicMock()
    user_client.get_user_by_public_key = AsyncMock()
    user_client.user_exists = AsyncMock()
    user_client.get_users_batch = AsyncMock(return_value={})

    service = ContactsService.__new__(ContactsService)
    service.session = session
    service.user_client = user_client

    contact_repo = MagicMock()
    blocked_repo = MagicMock()
    for name in (
        "exists",
        "create",
        "get_by_pair",
        "delete",
        "get_paginated",
        "count_by_user",
        "update",
        "delete_mutual",
    ):
        setattr(contact_repo, name, AsyncMock())
        setattr(blocked_repo, name, AsyncMock())
    service.contact_repo = contact_repo
    service.blocked_repo = blocked_repo
    return service


@pytest.fixture
def service() -> ContactsService:
    return _make_service()


def _contact(**overrides):
    base = dict(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        contact_user_id=uuid.uuid4(),
        created_at=datetime.utcnow(),
        is_favorite=False,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def _blocked(**overrides):
    base = dict(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        blocked_user_id=uuid.uuid4(),
        created_at=datetime.utcnow(),
    )
    base.update(overrides)
    return SimpleNamespace(**base)


# ---------------------------------------------------------------------------
# add_contact
# ---------------------------------------------------------------------------
class TestAddContact:
    async def test_happy_path(self, service):
        # Arrange
        uid = uuid.uuid4()
        cid = uuid.uuid4()
        service.user_client.get_user_by_public_key.return_value = str(cid)
        service.user_client.user_exists.return_value = (True, False)
        service.blocked_repo.exists.return_value = False
        service.contact_repo.exists.return_value = False
        service.contact_repo.create.return_value = _contact(
            user_id=uid,
            contact_user_id=cid,
        )
        service.user_client.get_users_batch.return_value = {
            str(cid): UserBriefProfile(user_id=str(cid), username="bob"),
        }

        # Act
        result = await service.add_contact(str(uid), "pub")

        # Assert
        assert result["contact_user_id"] == str(cid)
        assert result["profile"].username == "bob"
        service.contact_repo.create.assert_awaited_once()

    async def test_user_not_found_by_public_key(self, service):
        service.user_client.get_user_by_public_key.return_value = None
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.add_contact(str(uuid.uuid4()), "pub")

    async def test_cannot_add_self(self, service):
        uid = uuid.uuid4()
        service.user_client.get_user_by_public_key.return_value = str(uid)
        with pytest.raises(ValueError, match="Cannot add yourself"):
            await service.add_contact(str(uid), "pub")

    async def test_user_deleted(self, service):
        service.user_client.get_user_by_public_key.return_value = str(uuid.uuid4())
        service.user_client.user_exists.return_value = (True, True)
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.add_contact(str(uuid.uuid4()), "pub")

    async def test_blocked_user_not_found(self, service):
        service.user_client.get_user_by_public_key.return_value = str(uuid.uuid4())
        service.user_client.user_exists.return_value = (True, False)
        service.blocked_repo.exists.return_value = True
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.add_contact(str(uuid.uuid4()), "pub")

    async def test_already_exists(self, service):
        service.user_client.get_user_by_public_key.return_value = str(uuid.uuid4())
        service.user_client.user_exists.return_value = (True, False)
        service.blocked_repo.exists.return_value = False
        service.contact_repo.exists.return_value = True
        with pytest.raises(ValueError, match="ALREADY_EXISTS"):
            await service.add_contact(str(uuid.uuid4()), "pub")


# ---------------------------------------------------------------------------
# remove_contact
# ---------------------------------------------------------------------------
class TestRemoveContact:
    async def test_happy_path(self, service):
        service.contact_repo.get_by_pair.return_value = _contact()

        result = await service.remove_contact(str(uuid.uuid4()), str(uuid.uuid4()))

        assert result is True
        service.contact_repo.delete.assert_awaited_once()

    async def test_not_found(self, service):
        service.contact_repo.get_by_pair.return_value = None
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.remove_contact(str(uuid.uuid4()), str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# get_contacts
# ---------------------------------------------------------------------------
class TestGetContacts:
    async def test_happy_path(self, service):
        uid = uuid.uuid4()
        contacts = [_contact(user_id=uid) for _ in range(2)]
        service.contact_repo.get_paginated.return_value = contacts
        service.contact_repo.count_by_user.return_value = 2
        service.user_client.get_users_batch.return_value = {}

        result = await service.get_contacts(str(uid), limit=10, offset=0)

        assert result["total_count"] == 2
        assert len(result["contacts"]) == 2


# ---------------------------------------------------------------------------
# update_contact
# ---------------------------------------------------------------------------
class TestUpdateContact:
    async def test_updates_is_favorite(self, service):
        contact = _contact(is_favorite=False)
        service.contact_repo.get_by_pair.return_value = contact

        result = await service.update_contact(
            str(uuid.uuid4()),
            str(uuid.uuid4()),
            is_favorite=True,
        )

        assert "contact_user_id" in result
        service.contact_repo.update.assert_awaited_once()

    async def test_no_updates_when_no_fields(self, service):
        contact = _contact()
        service.contact_repo.get_by_pair.return_value = contact

        await service.update_contact(
            str(uuid.uuid4()),
            str(uuid.uuid4()),
            is_favorite=None,
        )

        service.contact_repo.update.assert_not_called()

    async def test_contact_not_found(self, service):
        service.contact_repo.get_by_pair.return_value = None
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.update_contact(str(uuid.uuid4()), str(uuid.uuid4()), True)


# ---------------------------------------------------------------------------
# block_user / unblock_user
# ---------------------------------------------------------------------------
class TestBlockUser:
    async def test_happy_path(self, service):
        uid = uuid.uuid4()
        buid = uuid.uuid4()
        service.blocked_repo.get_by_pair.return_value = None
        blocked_row = _blocked(user_id=uid, blocked_user_id=buid)
        service.blocked_repo.create.return_value = blocked_row

        result = await service.block_user(str(uid), str(buid))

        assert result["success"] is True
        service.contact_repo.delete_mutual.assert_awaited_once()

    async def test_cannot_block_self(self, service):
        uid = uuid.uuid4()
        with pytest.raises(ValueError, match="Cannot block yourself"):
            await service.block_user(str(uid), str(uid))

    async def test_already_blocked(self, service):
        service.blocked_repo.get_by_pair.return_value = _blocked()
        with pytest.raises(ValueError, match="ALREADY_EXISTS"):
            await service.block_user(str(uuid.uuid4()), str(uuid.uuid4()))


class TestUnblockUser:
    async def test_happy_path(self, service):
        service.blocked_repo.get_by_pair.return_value = _blocked()

        result = await service.unblock_user(str(uuid.uuid4()), str(uuid.uuid4()))

        assert result is True
        service.blocked_repo.delete.assert_awaited_once()

    async def test_not_found(self, service):
        service.blocked_repo.get_by_pair.return_value = None
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.unblock_user(str(uuid.uuid4()), str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# get_blocked_users
# ---------------------------------------------------------------------------
class TestGetBlockedUsers:
    async def test_happy_path(self, service):
        uid = uuid.uuid4()
        items = [_blocked(user_id=uid) for _ in range(3)]
        service.blocked_repo.get_paginated.return_value = items
        service.blocked_repo.count_by_user.return_value = 3
        service.user_client.get_users_batch.return_value = {}

        result = await service.get_blocked_users(str(uid), 10, 0)

        assert result["total_count"] == 3
        assert len(result["blocked_users"]) == 3


# ---------------------------------------------------------------------------
# is_contact / is_blocked
# ---------------------------------------------------------------------------
class TestIsContactIsBlocked:
    async def test_is_contact_delegates(self, service):
        service.contact_repo.exists.return_value = True
        assert await service.is_contact(str(uuid.uuid4()), str(uuid.uuid4())) is True

    async def test_is_blocked_delegates(self, service):
        service.blocked_repo.exists.return_value = False
        assert await service.is_blocked(str(uuid.uuid4()), str(uuid.uuid4())) is False
