import uuid
from datetime import datetime

from sqlalchemy import DateTime, LargeBinary, String, Text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database.base import Base


class DeviceRegistration(Base):
    __tablename__ = "device_registration"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    registration_code: Mapped[str] = mapped_column(String(12), unique=True, nullable=False, index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False, index=True)
    initiated_by_device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    status: Mapped[str] = mapped_column(String(30), default="pending")

    device_id: Mapped[str] = mapped_column(String(255), nullable=True)
    device_name: Mapped[str] = mapped_column(String(255), nullable=True)
    device_type: Mapped[str] = mapped_column(String(50), nullable=True)
    identity_key_pub: Mapped[bytes] = mapped_column(LargeBinary, nullable=True)
    init_key_pub: Mapped[bytes] = mapped_column(LargeBinary, nullable=True)
    credential_data: Mapped[bytes] = mapped_column(LargeBinary, nullable=True)

    confirmed_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    confirmed_by_device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    rejected_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    rejection_reason: Mapped[str] = mapped_column(Text, nullable=True)

    confirmed_device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    result_access_token: Mapped[str] = mapped_column(String(512), nullable=True)
    result_refresh_token: Mapped[str] = mapped_column(String(512), nullable=True)
    result_token_expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
