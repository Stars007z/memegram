import uuid
from datetime import datetime

from sqlalchemy import DateTime, Index, Integer, LargeBinary
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database.base import Base


class MlsKeyPackage(Base):
    __tablename__ = "mls_key_packages"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4,
    )
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    key_package_data: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    key_package_ref: Mapped[bytes] = mapped_column(LargeBinary, unique=True, nullable=False)
    cipher_suite: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    __table_args__ = (
        Index(
            "ix_key_packages_available",
            "user_id", "device_id",
            postgresql_where=consumed_at.is_(None),
        ),
    )
