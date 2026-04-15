import uuid
from datetime import datetime, timedelta
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.storage_item import StorageItem
from app.infrastructure import s3_client
from app.config import settings


ITEM_TYPE_CONFIG: dict[str, dict] = {
    "avatar": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 5 * 1024 * 1024,
        "access_policy": "public",
    },
    "profile_background": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 10 * 1024 * 1024,
        "access_policy": "public",
    },
    "chat_background": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 10 * 1024 * 1024,
        "access_policy": "owner_only",
    },
    "top_bar": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 5 * 1024 * 1024,
        "access_policy": "owner_only",
    },
    "my_bubble": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 5 * 1024 * 1024,
        "access_policy": "owner_only",
    },
    "their_bubble": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 5 * 1024 * 1024,
        "access_policy": "owner_only",
    },
    "notification_sound": {
        "allowed_mimes": {"audio/ogg", "audio/mpeg", "audio/aac"},
        "max_size": 1 * 1024 * 1024,
        "access_policy": "owner_only",
    },
    "ringtone": {
        "allowed_mimes": {"audio/ogg", "audio/mpeg", "audio/aac"},
        "max_size": 5 * 1024 * 1024,
        "access_policy": "owner_only",
    },
    "group_avatar": {
        "allowed_mimes": {"image/jpeg", "image/png", "image/webp"},
        "max_size": 5 * 1024 * 1024,
        "access_policy": "public",
    },
}


def _now() -> datetime:
    return datetime.utcnow()


class ItemStorageService:
    def __init__(self, session: AsyncSession):
        self.session = session

    # ── InitiateUpload ──────────────────────────────────────────────

    async def initiate_upload(
        self, owner_user_id: str, item_type: str, mime_type: str, size_bytes: int,
    ) -> tuple[str, str, int]:
        cfg = ITEM_TYPE_CONFIG.get(item_type)
        if cfg is None:
            raise ValueError(f"Unknown item_type: {item_type}")
        if mime_type not in cfg["allowed_mimes"]:
            raise ValueError(f"Unsupported mime_type '{mime_type}' for {item_type}")
        if size_bytes <= 0 or size_bytes > cfg["max_size"]:
            raise ValueError(
                f"size_bytes must be 1..{cfg['max_size']} for {item_type}, got {size_bytes}"
            )

        item_id = uuid.uuid4()
        s3_key = f"items/{owner_user_id}/{item_type}/{item_id}"

        item = StorageItem(
            id=item_id,
            owner_user_id=uuid.UUID(owner_user_id),
            item_type=item_type,
            s3_bucket=settings.S3_BUCKET_NAME,
            s3_key=s3_key,
            mime_type=mime_type,
            size_bytes=size_bytes,
            access_policy=cfg["access_policy"],
            status="pending",
            created_at=_now(),
        )
        self.session.add(item)
        await self.session.flush()

        upload_url = await s3_client.generate_presigned_upload_url(
            bucket=settings.S3_BUCKET_NAME,
            key=s3_key,
            mime_type=mime_type,
            ttl=settings.PRESIGNED_UPLOAD_TTL,
        )
        expires_at = int((_now() + timedelta(seconds=settings.PRESIGNED_UPLOAD_TTL)).timestamp())

        return str(item_id), upload_url, expires_at

    # ── ConfirmUpload ───────────────────────────────────────────────

    async def confirm_upload(self, owner_user_id: str, item_id: str) -> bool:
        result = await self.session.execute(
            select(StorageItem).where(
                StorageItem.id == uuid.UUID(item_id),
                StorageItem.status == "pending",
            )
        )
        item = result.scalar_one_or_none()
        if item is None:
            raise FileNotFoundError("Item not found or not in pending state")

        if str(item.owner_user_id) != owner_user_id:
            raise PermissionError("Not the owner of this item")

        head = await s3_client.head_object(item.s3_bucket, item.s3_key)
        actual_size = head.get("ContentLength", 0)
        tolerance = item.size_bytes * 0.01
        if abs(actual_size - item.size_bytes) > max(tolerance, 1):
            raise ValueError(
                f"Size mismatch: expected ~{item.size_bytes}, got {actual_size}"
            )

        item.status = "uploaded"
        item.uploaded_at = _now()
        await self.session.flush()
        return True

    # ── GetDownloadUrl ──────────────────────────────────────────────

    async def get_download_url(
        self, item_id: str, requester_user_id: str,
    ) -> tuple[str, int, str]:
        result = await self.session.execute(
            select(StorageItem).where(
                StorageItem.id == uuid.UUID(item_id),
                StorageItem.status == "uploaded",
            )
        )
        item = result.scalar_one_or_none()
        if item is None:
            raise FileNotFoundError("Item not found")

        if item.access_policy == "owner_only" and str(item.owner_user_id) != requester_user_id:
            raise PermissionError("Access denied")

        download_url = await s3_client.generate_presigned_download_url(
            bucket=item.s3_bucket,
            key=item.s3_key,
            ttl=settings.PRESIGNED_DOWNLOAD_TTL,
        )
        expires_at = int((_now() + timedelta(seconds=settings.PRESIGNED_DOWNLOAD_TTL)).timestamp())
        return download_url, expires_at, item.mime_type

    # ── DeleteItem ──────────────────────────────────────────────────

    async def delete_item(self, owner_user_id: str, item_id: str) -> bool:
        result = await self.session.execute(
            select(StorageItem).where(
                StorageItem.id == uuid.UUID(item_id),
                StorageItem.status != "deleted",
            )
        )
        item = result.scalar_one_or_none()
        if item is None:
            raise FileNotFoundError("Item not found")

        if str(item.owner_user_id) != owner_user_id:
            raise PermissionError("Not the owner of this item")

        await s3_client.delete_object(item.s3_bucket, item.s3_key)

        item.status = "deleted"
        item.deleted_at = _now()
        await self.session.flush()
        return True

    # ── DeleteUserItems ─────────────────────────────────────────────

    async def delete_user_items(
        self, owner_user_id: str, item_types: list[str] | None = None,
    ) -> int:
        uid = uuid.UUID(owner_user_id)
        query = select(StorageItem).where(
            StorageItem.owner_user_id == uid,
            StorageItem.status == "uploaded",
        )
        if item_types:
            query = query.where(StorageItem.item_type.in_(item_types))

        result = await self.session.execute(query)
        items = result.scalars().all()
        if not items:
            return 0

        keys = [item.s3_key for item in items]
        await s3_client.delete_objects(settings.S3_BUCKET_NAME, keys)

        now = _now()
        ids = [item.id for item in items]
        await self.session.execute(
            update(StorageItem)
            .where(StorageItem.id.in_(ids))
            .values(status="deleted", deleted_at=now)
        )
        await self.session.flush()
        return len(ids)

    # ── CleanupPendingUploads ───────────────────────────────────────

    async def cleanup_pending_uploads(
        self, older_than_seconds: int = 7200, batch_size: int = 100,
    ) -> int:
        cutoff = _now() - timedelta(seconds=older_than_seconds)
        result = await self.session.execute(
            select(StorageItem)
            .where(
                StorageItem.status == "pending",
                StorageItem.created_at < cutoff,
            )
            .limit(batch_size)
        )
        items = result.scalars().all()
        if not items:
            return 0

        for item in items:
            try:
                await s3_client.delete_object(item.s3_bucket, item.s3_key)
            except Exception:
                pass

        now = _now()
        ids = [item.id for item in items]
        await self.session.execute(
            update(StorageItem)
            .where(StorageItem.id.in_(ids))
            .values(status="deleted", deleted_at=now)
        )
        await self.session.flush()
        return len(ids)

    # ── GetItemMetadata ─────────────────────────────────────────────

    async def get_item_metadata(
        self, item_id: str, requester_user_id: str,
    ) -> StorageItem:
        result = await self.session.execute(
            select(StorageItem).where(
                StorageItem.id == uuid.UUID(item_id),
                StorageItem.status == "uploaded",
            )
        )
        item = result.scalar_one_or_none()
        if item is None:
            raise FileNotFoundError("Item not found")

        if item.access_policy == "owner_only" and str(item.owner_user_id) != requester_user_id:
            raise PermissionError("Access denied")

        return item
