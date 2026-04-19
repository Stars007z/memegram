import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Index, String, text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database.base import Base


class StorageItem(Base):
    __tablename__ = "storage_items"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    owner_user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    item_type: Mapped[str] = mapped_column(String(50), nullable=False)
    s3_bucket: Mapped[str] = mapped_column(String(255), nullable=False)
    s3_key: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    mime_type: Mapped[str] = mapped_column(String(100), nullable=False)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    access_policy: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="pending")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)
    uploaded_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    deleted_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)

    __table_args__ = (
        Index("ix_storage_items_owner_type", "owner_user_id", "item_type"),
        Index(
            "ix_storage_items_pending_status",
            "status",
            postgresql_where=text("status = 'pending'"),
        ),
    )
