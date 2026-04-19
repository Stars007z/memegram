"""Unit tests for `app.services.item_storage_service.ItemStorageService`.

Тесты AAA, мокают AsyncSession и модуль `app.infrastructure.s3_client`,
чтобы проверить именно бизнес-логику: валидацию лимитов/mime, проверку
прав, корректный lifecycle (pending -> uploaded -> deleted).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.item_storage_service import ItemStorageService


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_service() -> ItemStorageService:
    session = MagicMock()
    session.execute = AsyncMock()
    session.flush = AsyncMock()
    session.add = MagicMock()
    return ItemStorageService(session)


@pytest.fixture
def service() -> ItemStorageService:
    return _make_service()


@pytest.fixture
def s3_mock():
    with patch("app.services.item_storage_service.s3_client") as s3:
        s3.generate_presigned_upload_url = AsyncMock(return_value="https://s3/upload")
        s3.generate_presigned_download_url = AsyncMock(return_value="https://s3/download")
        s3.head_object = AsyncMock(return_value={"ContentLength": 1000})
        s3.delete_object = AsyncMock()
        s3.delete_objects = AsyncMock()
        yield s3


def _item(**overrides):
    base = dict(
        id=uuid.uuid4(),
        owner_user_id=uuid.uuid4(),
        item_type="avatar",
        s3_bucket="test-bucket",
        s3_key="items/abc/avatar/xyz",
        mime_type="image/png",
        size_bytes=1000,
        access_policy="public",
        status="uploaded",
        created_at=datetime.utcnow(),
        uploaded_at=None,
        deleted_at=None,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def _wrap_scalar_one_or_none(value):
    result = MagicMock()
    result.scalar_one_or_none = MagicMock(return_value=value)
    return result


def _wrap_scalars(items):
    scalars = MagicMock()
    scalars.all = MagicMock(return_value=items)
    result = MagicMock()
    result.scalars = MagicMock(return_value=scalars)
    return result


# ---------------------------------------------------------------------------
# initiate_upload
# ---------------------------------------------------------------------------
class TestInitiateUpload:
    async def test_happy_path(self, service, s3_mock):
        # Arrange
        owner = str(uuid.uuid4())

        # Act
        item_id, upload_url, expires_at = await service.initiate_upload(
            owner_user_id=owner,
            item_type="avatar",
            mime_type="image/png",
            size_bytes=2048,
        )

        # Assert
        assert uuid.UUID(item_id)
        assert upload_url == "https://s3/upload"
        assert expires_at > 0
        service.session.add.assert_called_once()
        service.session.flush.assert_awaited_once()

    async def test_rejects_unknown_item_type(self, service, s3_mock):
        with pytest.raises(ValueError, match="Unknown item_type"):
            await service.initiate_upload(str(uuid.uuid4()), "bogus", "image/png", 1)

    async def test_rejects_bad_mime(self, service, s3_mock):
        with pytest.raises(ValueError, match="Unsupported mime_type"):
            await service.initiate_upload(str(uuid.uuid4()), "avatar", "text/plain", 1)

    async def test_rejects_too_large(self, service, s3_mock):
        with pytest.raises(ValueError, match="size_bytes must be"):
            await service.initiate_upload(
                str(uuid.uuid4()),
                "avatar",
                "image/png",
                100 * 1024 * 1024,
            )

    async def test_rejects_zero_size(self, service, s3_mock):
        with pytest.raises(ValueError, match="size_bytes must be"):
            await service.initiate_upload(str(uuid.uuid4()), "avatar", "image/png", 0)


# ---------------------------------------------------------------------------
# confirm_upload
# ---------------------------------------------------------------------------
class TestConfirmUpload:
    async def test_happy_path(self, service, s3_mock):
        # Arrange
        owner = str(uuid.uuid4())
        item = _item(
            owner_user_id=uuid.UUID(owner),
            status="pending",
            size_bytes=1000,
        )
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)
        s3_mock.head_object.return_value = {"ContentLength": 1000}

        # Act
        result = await service.confirm_upload(owner, str(item.id))

        # Assert
        assert result is True
        assert item.status == "uploaded"

    async def test_not_found_raises(self, service, s3_mock):
        service.session.execute.return_value = _wrap_scalar_one_or_none(None)
        with pytest.raises(FileNotFoundError):
            await service.confirm_upload(str(uuid.uuid4()), str(uuid.uuid4()))

    async def test_not_owner_raises(self, service, s3_mock):
        item = _item(status="pending", size_bytes=1000)
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)
        with pytest.raises(PermissionError):
            await service.confirm_upload(str(uuid.uuid4()), str(item.id))

    async def test_size_mismatch_raises(self, service, s3_mock):
        owner = str(uuid.uuid4())
        item = _item(
            owner_user_id=uuid.UUID(owner),
            status="pending",
            size_bytes=1000,
        )
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)
        s3_mock.head_object.return_value = {"ContentLength": 5000}
        with pytest.raises(ValueError, match="Size mismatch"):
            await service.confirm_upload(owner, str(item.id))


# ---------------------------------------------------------------------------
# get_download_url
# ---------------------------------------------------------------------------
class TestGetDownloadUrl:
    async def test_public_item_any_requester(self, service, s3_mock):
        item = _item(access_policy="public")
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)

        url, exp, mime = await service.get_download_url(
            str(item.id),
            str(uuid.uuid4()),
        )

        assert url == "https://s3/download"
        assert exp > 0
        assert mime == item.mime_type

    async def test_owner_only_denied_for_others(self, service, s3_mock):
        item = _item(access_policy="owner_only")
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)

        with pytest.raises(PermissionError):
            await service.get_download_url(str(item.id), str(uuid.uuid4()))

    async def test_owner_only_allowed_for_owner(self, service, s3_mock):
        owner = uuid.uuid4()
        item = _item(access_policy="owner_only", owner_user_id=owner)
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)

        url, _, _ = await service.get_download_url(str(item.id), str(owner))
        assert url == "https://s3/download"

    async def test_not_found(self, service, s3_mock):
        service.session.execute.return_value = _wrap_scalar_one_or_none(None)
        with pytest.raises(FileNotFoundError):
            await service.get_download_url(str(uuid.uuid4()), str(uuid.uuid4()))


# ---------------------------------------------------------------------------
# delete_item
# ---------------------------------------------------------------------------
class TestDeleteItem:
    async def test_happy_path(self, service, s3_mock):
        owner = str(uuid.uuid4())
        item = _item(owner_user_id=uuid.UUID(owner))
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)

        result = await service.delete_item(owner, str(item.id))

        assert result is True
        assert item.status == "deleted"
        s3_mock.delete_object.assert_awaited_once()

    async def test_not_found(self, service, s3_mock):
        service.session.execute.return_value = _wrap_scalar_one_or_none(None)
        with pytest.raises(FileNotFoundError):
            await service.delete_item(str(uuid.uuid4()), str(uuid.uuid4()))

    async def test_not_owner(self, service, s3_mock):
        item = _item()
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)
        with pytest.raises(PermissionError):
            await service.delete_item(str(uuid.uuid4()), str(item.id))


# ---------------------------------------------------------------------------
# delete_user_items
# ---------------------------------------------------------------------------
class TestDeleteUserItems:
    async def test_empty_returns_zero(self, service, s3_mock):
        service.session.execute.return_value = _wrap_scalars([])

        result = await service.delete_user_items(str(uuid.uuid4()))

        assert result == 0
        s3_mock.delete_objects.assert_not_called()

    async def test_deletes_items(self, service, s3_mock):
        owner = str(uuid.uuid4())
        items = [_item(owner_user_id=uuid.UUID(owner)) for _ in range(3)]
        # First call returns items, second is the UPDATE (ignored).
        service.session.execute.side_effect = [_wrap_scalars(items), MagicMock()]

        result = await service.delete_user_items(owner)

        assert result == 3
        s3_mock.delete_objects.assert_awaited_once()

    async def test_filters_by_item_types(self, service, s3_mock):
        owner = str(uuid.uuid4())
        items = [_item(owner_user_id=uuid.UUID(owner), item_type="avatar")]
        service.session.execute.side_effect = [_wrap_scalars(items), MagicMock()]

        result = await service.delete_user_items(owner, item_types=["avatar"])

        assert result == 1


# ---------------------------------------------------------------------------
# cleanup_pending_uploads
# ---------------------------------------------------------------------------
class TestCleanupPendingUploads:
    async def test_empty_returns_zero(self, service, s3_mock):
        service.session.execute.return_value = _wrap_scalars([])

        result = await service.cleanup_pending_uploads()

        assert result == 0

    async def test_deletes_pending_and_ignores_s3_errors(self, service, s3_mock):
        items = [
            _item(status="pending", created_at=datetime.utcnow() - timedelta(days=1)),
            _item(status="pending", created_at=datetime.utcnow() - timedelta(days=1)),
        ]
        service.session.execute.side_effect = [_wrap_scalars(items), MagicMock()]
        s3_mock.delete_object.side_effect = [None, RuntimeError("boom")]

        result = await service.cleanup_pending_uploads()

        assert result == 2


# ---------------------------------------------------------------------------
# get_item_metadata
# ---------------------------------------------------------------------------
class TestGetItemMetadata:
    async def test_happy_path_public(self, service, s3_mock):
        item = _item(access_policy="public")
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)

        result = await service.get_item_metadata(str(item.id), str(uuid.uuid4()))

        assert result is item

    async def test_owner_only_denied(self, service, s3_mock):
        item = _item(access_policy="owner_only")
        service.session.execute.return_value = _wrap_scalar_one_or_none(item)
        with pytest.raises(PermissionError):
            await service.get_item_metadata(str(item.id), str(uuid.uuid4()))

    async def test_not_found(self, service, s3_mock):
        service.session.execute.return_value = _wrap_scalar_one_or_none(None)
        with pytest.raises(FileNotFoundError):
            await service.get_item_metadata(str(uuid.uuid4()), str(uuid.uuid4()))
