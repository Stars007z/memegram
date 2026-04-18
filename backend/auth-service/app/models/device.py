import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, LargeBinary, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.base import Base


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False, index=True)

    client_device_id: Mapped[str] = mapped_column(String(255), unique=True, nullable=False, index=True)
    device_name: Mapped[str] = mapped_column(String(255), nullable=True)
    device_type: Mapped[str] = mapped_column(String(50), default="secondary")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    last_seen: Mapped[datetime] = mapped_column(DateTime, nullable=True)

    identity_key_pub: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    init_key_pub: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    credential_data: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)

    revoked_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    revoked_by_device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)

    sessions: Mapped[list["Session"]] = relationship(back_populates="device", cascade="all, delete-orphan")
