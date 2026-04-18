"""Unit tests for `app.services.media_service.MediaServiceImpl`.

Тесты строятся по схеме AAA (Arrange / Act / Assert) и мокают репозитории
и gRPC media-client, чтобы проверить именно бизнес-логику загрузок медиа.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import grpc
import pytest

from app.services.media_service import MediaServiceImpl


# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------
def _make_repo(*method_names: str) -> MagicMock:
    repo = MagicMock()
    for name in method_names:
        setattr(repo, name, AsyncMock())
    return repo


@pytest.fixture
def attachment_repo() -> MagicMock:
    return _make_repo("create", "update", "get_by_id")


@pytest.fixture
def member_repo() -> MagicMock:
    return _make_repo("is_member")


@pytest.fixture
def media_client() -> MagicMock:
    client = MagicMock()
    client.get_upload_presigned_url = AsyncMock()
    client.verify_object_exists = AsyncMock()
    client.get_download_presigned_url = AsyncMock()
    client.delete_object = AsyncMock()
    return client


@pytest.fixture
def service(attachment_repo, member_repo, media_client) -> MediaServiceImpl:
    return MediaServiceImpl(attachment_repo, member_repo, media_client)


def _make_rpc_error() -> grpc.RpcError:
    """Создаёт минимальный grpc.RpcError без реальной сети."""
    return grpc.RpcError("boom")


# ---------------------------------------------------------------------------
# initiate_upload
# ---------------------------------------------------------------------------
class TestInitiateUpload:
    async def test_happy_path_creates_attachment_and_returns_url(
        self,
        service,
        attachment_repo,
        member_repo,
        media_client,
    ):
        # Arrange
        user_id = uuid.uuid4()
        conv_id = uuid.uuid4()
        media_id = uuid.uuid4()
        member_repo.is_member.return_value = True
        attachment_repo.create.return_value = SimpleNamespace(id=media_id)
        media_client.get_upload_presigned_url.return_value = SimpleNamespace(
            upload_url="https://example.com/put",
            s3_key="bucket/key",
        )

        # Act
        result = await service.initiate_upload(
            user_id=user_id,
            conversation_id=conv_id,
            mime_type="image/png",
            encrypted_size=1024,
            encryption_metadata=b"meta",
        )

        # Assert
        assert result.media_id == media_id
        assert result.upload_url == "https://example.com/put"
        attachment_repo.create.assert_awaited_once()
        attachment_repo.update.assert_awaited_once()
        update_args = attachment_repo.update.await_args.args
        assert update_args[1] == {"s3_key": "bucket/key"}

    async def test_rejects_non_member(self, service, member_repo):
        # Arrange
        member_repo.is_member.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="PERMISSION_DENIED"):
            await service.initiate_upload(
                user_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                mime_type="image/png",
                encrypted_size=10,
                encryption_metadata=b"",
            )

    async def test_rejects_file_too_large(self, service, member_repo):
        # Arrange
        member_repo.is_member.return_value = True

        # Act / Assert
        with pytest.raises(ValueError, match="File too large"):
            await service.initiate_upload(
                user_id=uuid.uuid4(),
                conversation_id=uuid.uuid4(),
                mime_type="image/png",
                encrypted_size=10**12,
                encryption_metadata=b"",
            )


# ---------------------------------------------------------------------------
# confirm_upload
# ---------------------------------------------------------------------------
class TestConfirmUpload:
    async def test_happy_path(self, service, attachment_repo, media_client):
        # Arrange
        user_id = uuid.uuid4()
        media_id = uuid.uuid4()
        attachment = SimpleNamespace(
            id=media_id,
            uploader_user_id=user_id,
            s3_key="bucket/key",
        )
        attachment_repo.get_by_id.return_value = attachment
        media_client.verify_object_exists.return_value = (True, 123)

        # Act
        ok = await service.confirm_upload(user_id, media_id)

        # Assert
        assert ok is True
        attachment_repo.update.assert_awaited_once()
        update_kwargs = attachment_repo.update.await_args.args[1]
        assert isinstance(update_kwargs["confirmed_at"], datetime)

    async def test_missing_attachment(self, service, attachment_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.confirm_upload(uuid.uuid4(), uuid.uuid4())

    async def test_wrong_uploader(self, service, attachment_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            uploader_user_id=uuid.uuid4(),
            s3_key="k",
        )

        # Act / Assert
        with pytest.raises(ValueError, match="Not the uploader"):
            await service.confirm_upload(uuid.uuid4(), uuid.uuid4())

    async def test_missing_s3_key(self, service, attachment_repo):
        # Arrange
        user_id = uuid.uuid4()
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            uploader_user_id=user_id,
            s3_key=None,
        )

        # Act / Assert
        with pytest.raises(ValueError, match="Upload was not initiated"):
            await service.confirm_upload(user_id, uuid.uuid4())

    async def test_s3_object_missing(self, service, attachment_repo, media_client):
        # Arrange
        user_id = uuid.uuid4()
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            uploader_user_id=user_id,
            s3_key="k",
        )
        media_client.verify_object_exists.return_value = (False, 0)

        # Act / Assert
        with pytest.raises(ValueError, match="File not found"):
            await service.confirm_upload(user_id, uuid.uuid4())


# ---------------------------------------------------------------------------
# get_download_url
# ---------------------------------------------------------------------------
class TestGetDownloadUrl:
    async def test_happy_path(
        self,
        service,
        attachment_repo,
        member_repo,
        media_client,
    ):
        # Arrange
        user_id = uuid.uuid4()
        conv_id = uuid.uuid4()
        media_id = uuid.uuid4()
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            conversation_id=conv_id,
            confirmed_at=datetime.utcnow(),
            s3_key="k",
            encryption_metadata=b"meta",
        )
        member_repo.is_member.return_value = True
        media_client.get_download_presigned_url.return_value = SimpleNamespace(
            download_url="https://example.com/get",
        )

        # Act
        result = await service.get_download_url(user_id, media_id)

        # Assert
        assert result.download_url == "https://example.com/get"
        assert result.encryption_metadata == b"meta"

    async def test_missing_attachment(self, service, attachment_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = None

        # Act / Assert
        with pytest.raises(ValueError, match="NOT_FOUND"):
            await service.get_download_url(uuid.uuid4(), uuid.uuid4())

    async def test_non_member(self, service, attachment_repo, member_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            conversation_id=uuid.uuid4(),
            confirmed_at=datetime.utcnow(),
            s3_key="k",
            encryption_metadata=b"",
        )
        member_repo.is_member.return_value = False

        # Act / Assert
        with pytest.raises(ValueError, match="PERMISSION_DENIED"):
            await service.get_download_url(uuid.uuid4(), uuid.uuid4())

    async def test_not_confirmed(self, service, attachment_repo, member_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            conversation_id=uuid.uuid4(),
            confirmed_at=None,
            s3_key="k",
            encryption_metadata=b"",
        )
        member_repo.is_member.return_value = True

        # Act / Assert
        with pytest.raises(ValueError, match="Upload not confirmed"):
            await service.get_download_url(uuid.uuid4(), uuid.uuid4())

    async def test_missing_s3_key(self, service, attachment_repo, member_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = SimpleNamespace(
            conversation_id=uuid.uuid4(),
            confirmed_at=datetime.utcnow(),
            s3_key=None,
            encryption_metadata=b"",
        )
        member_repo.is_member.return_value = True

        # Act / Assert
        with pytest.raises(ValueError, match="Missing s3_key"):
            await service.get_download_url(uuid.uuid4(), uuid.uuid4())


# ---------------------------------------------------------------------------
# delete_media
# ---------------------------------------------------------------------------
class TestDeleteMedia:
    async def test_happy_path(self, service, attachment_repo, media_client):
        # Arrange
        attachment = SimpleNamespace(s3_key="k")
        attachment_repo.get_by_id.return_value = attachment

        # Act
        ok = await service.delete_media(uuid.uuid4())

        # Assert
        assert ok is True
        media_client.delete_object.assert_awaited_once()
        attachment_repo.update.assert_awaited_once()
        assert attachment_repo.update.await_args.args[1] == {
            "confirmed_at": None,
            "s3_key": None,
        }

    async def test_no_attachment_returns_false(self, service, attachment_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = None

        # Act
        ok = await service.delete_media(uuid.uuid4())

        # Assert
        assert ok is False

    async def test_no_s3_key_returns_false(self, service, attachment_repo):
        # Arrange
        attachment_repo.get_by_id.return_value = SimpleNamespace(s3_key=None)

        # Act
        ok = await service.delete_media(uuid.uuid4())

        # Assert
        assert ok is False

    async def test_rpc_error_returns_false(
        self,
        service,
        attachment_repo,
        media_client,
    ):
        # Arrange
        attachment_repo.get_by_id.return_value = SimpleNamespace(s3_key="k")
        media_client.delete_object.side_effect = _make_rpc_error()

        # Act
        ok = await service.delete_media(uuid.uuid4())

        # Assert
        assert ok is False
        attachment_repo.update.assert_not_awaited()
