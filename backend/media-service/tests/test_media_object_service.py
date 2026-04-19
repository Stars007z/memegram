"""Unit tests for `app.services.media_object_service.MediaObjectServiceImpl`.

Тесты AAA, мокают MediaObjectRepository и S3Client, чтобы проверить именно
бизнес-логику presigned-URL'ов, верификации и батчевого удаления.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.services.interfaces.media_object_service import (
    BatchDeleteResult,
    DownloadPresignedResult,
    UploadPresignedResult,
    VerifyResult,
)
from app.services.media_object_service import MediaObjectServiceImpl


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_service() -> MediaObjectServiceImpl:
    repo = MagicMock()
    for name in (
        "create",
        "get_by_id",
        "get_by_s3_key",
        "mark_uploaded",
        "mark_deleted",
        "mark_deleted_batch",
        "get_expired",
    ):
        setattr(repo, name, AsyncMock())

    s3 = MagicMock()
    s3.generate_presigned_upload_url = AsyncMock(return_value="https://s3/upload")
    s3.generate_presigned_download_url = AsyncMock(return_value="https://s3/download")
    s3.head_object = AsyncMock()
    s3.delete_object = AsyncMock()
    s3.delete_objects = AsyncMock(return_value=(0, []))

    service = MediaObjectServiceImpl(repo=repo, s3=s3)
    return service


@pytest.fixture
def service() -> MediaObjectServiceImpl:
    return _make_service()


def _obj(**overrides):
    base = dict(
        id=uuid.uuid4(),
        s3_bucket="test-bucket",
        s3_key="k",
        mime_type="image/png",
        encrypted_size=1000,
        status="uploaded",
    )
    base.update(overrides)
    return SimpleNamespace(**base)


# ---------------------------------------------------------------------------
# get_upload_presigned_url
# ---------------------------------------------------------------------------
class TestGetUploadPresignedUrl:
    async def test_happy_path(self, service):
        # Arrange
        media_id = uuid.uuid4()
        conv_id = uuid.uuid4()

        # Act
        result = await service.get_upload_presigned_url(
            media_id=media_id,
            conversation_id=conv_id,
            mime_type="image/png",
            encrypted_size=42,
            expires_in_seconds=3600,
        )

        # Assert
        assert isinstance(result, UploadPresignedResult)
        assert result.s3_key == f"media/{conv_id}/{media_id}/image"
        assert result.upload_url == "https://s3/upload"
        assert result.expires_at > 0
        service._repo.create.assert_awaited_once()
        service._s3.generate_presigned_upload_url.assert_awaited_once()

    async def test_unknown_mime_prefix_defaults_to_unknown(self, service):
        # Arrange / Act
        result = await service.get_upload_presigned_url(
            media_id=uuid.uuid4(),
            conversation_id=uuid.uuid4(),
            mime_type="weirdstuff",
            encrypted_size=1,
            expires_in_seconds=1,
        )

        # Assert
        assert result.s3_key.endswith("/unknown")


# ---------------------------------------------------------------------------
# verify_object_exists
# ---------------------------------------------------------------------------
class TestVerifyObjectExists:
    async def test_happy_path_marks_uploaded(self, service):
        # Arrange
        media_id = uuid.uuid4()
        service._repo.get_by_id.return_value = _obj(encrypted_size=1000)
        service._s3.head_object.return_value = {"content_length": 1000}

        # Act
        result = await service.verify_object_exists(media_id, "k")

        # Assert
        assert isinstance(result, VerifyResult)
        assert result.exists is True
        assert result.actual_size == 1000
        service._repo.mark_uploaded.assert_awaited_once_with(media_id)

    async def test_returns_not_exists_when_row_missing(self, service):
        # Arrange
        service._repo.get_by_id.return_value = None

        # Act
        result = await service.verify_object_exists(uuid.uuid4(), "k")

        # Assert
        assert result.exists is False
        service._repo.mark_uploaded.assert_not_called()

    async def test_returns_not_exists_when_s3_missing(self, service):
        # Arrange
        service._repo.get_by_id.return_value = _obj()
        service._s3.head_object.return_value = None

        # Act
        result = await service.verify_object_exists(uuid.uuid4(), "k")

        # Assert
        assert result.exists is False

    async def test_size_mismatch_rejects(self, service):
        # Arrange
        service._repo.get_by_id.return_value = _obj(encrypted_size=1000)
        service._s3.head_object.return_value = {"content_length": 2000}

        # Act
        result = await service.verify_object_exists(uuid.uuid4(), "k")

        # Assert
        assert result.exists is False
        assert result.actual_size == 2000
        service._repo.mark_uploaded.assert_not_called()

    async def test_zero_expected_size_skips_size_check(self, service):
        # Arrange
        service._repo.get_by_id.return_value = _obj(encrypted_size=0)
        service._s3.head_object.return_value = {"content_length": 999}

        # Act
        result = await service.verify_object_exists(uuid.uuid4(), "k")

        # Assert
        assert result.exists is True


# ---------------------------------------------------------------------------
# get_download_presigned_url
# ---------------------------------------------------------------------------
class TestGetDownloadPresignedUrl:
    async def test_happy_path(self, service):
        # Arrange
        service._repo.get_by_s3_key.return_value = _obj(status="uploaded")

        # Act
        result = await service.get_download_presigned_url("k", 60)

        # Assert
        assert isinstance(result, DownloadPresignedResult)
        assert result.download_url == "https://s3/download"

    async def test_rejects_missing_object(self, service):
        # Arrange
        service._repo.get_by_s3_key.return_value = None

        # Act / Assert
        with pytest.raises(FileNotFoundError):
            await service.get_download_presigned_url("k", 60)

    async def test_rejects_not_uploaded_status(self, service):
        # Arrange
        service._repo.get_by_s3_key.return_value = _obj(status="pending")

        # Act / Assert
        with pytest.raises(FileNotFoundError):
            await service.get_download_presigned_url("k", 60)


# ---------------------------------------------------------------------------
# delete_object
# ---------------------------------------------------------------------------
class TestDeleteObject:
    async def test_happy_path(self, service):
        # Arrange
        media_id = uuid.uuid4()
        service._repo.get_by_id.return_value = _obj()

        # Act
        result = await service.delete_object(media_id, "k")

        # Assert
        assert result is True
        service._s3.delete_object.assert_awaited_once()
        service._repo.mark_deleted.assert_awaited_once_with(media_id)

    async def test_missing_object_returns_false(self, service):
        # Arrange
        service._repo.get_by_id.return_value = None

        # Act
        result = await service.delete_object(uuid.uuid4(), "k")

        # Assert
        assert result is False
        service._s3.delete_object.assert_not_called()


# ---------------------------------------------------------------------------
# delete_objects_batch
# ---------------------------------------------------------------------------
class TestDeleteObjectsBatch:
    async def test_empty_input(self, service):
        # Act
        result = await service.delete_objects_batch([])

        # Assert
        assert isinstance(result, BatchDeleteResult)
        assert result.deleted_count == 0
        assert result.failed == []
        service._s3.delete_objects.assert_not_called()

    async def test_all_success(self, service):
        # Arrange
        ids = [(uuid.uuid4(), f"k{i}") for i in range(3)]
        service._s3.delete_objects.return_value = (3, [])

        # Act
        result = await service.delete_objects_batch(ids)

        # Assert
        assert result.deleted_count == 3
        assert result.failed == []
        service._repo.mark_deleted_batch.assert_awaited_once()

    async def test_partial_failures(self, service):
        # Arrange
        mid1, mid2 = uuid.uuid4(), uuid.uuid4()
        ids = [(mid1, "k1"), (mid2, "k2")]
        service._s3.delete_objects.return_value = (
            1,
            [{"key": "k2", "message": "access denied"}],
        )

        # Act
        result = await service.delete_objects_batch(ids)

        # Assert
        assert result.deleted_count == 1
        assert len(result.failed) == 1
        assert result.failed[0].media_id == str(mid2)
        assert result.failed[0].error == "access denied"
        service._repo.mark_deleted_batch.assert_awaited_once_with([mid1])


# ---------------------------------------------------------------------------
# process_expired_objects
# ---------------------------------------------------------------------------
class TestProcessExpiredObjects:
    async def test_no_expired_returns_zero(self, service):
        # Arrange
        service._repo.get_expired.return_value = []

        # Act
        result = await service.process_expired_objects(10)

        # Assert
        assert result == 0
        service._s3.delete_objects.assert_not_called()

    async def test_deletes_expired(self, service):
        # Arrange
        expired = [_obj(id=uuid.uuid4(), s3_key="k1"), _obj(id=uuid.uuid4(), s3_key="k2")]
        service._repo.get_expired.return_value = expired
        service._s3.delete_objects.return_value = (2, [])

        # Act
        result = await service.process_expired_objects(10)

        # Assert
        assert result == 2
