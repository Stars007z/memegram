from sqlalchemy import Column, String, Boolean, DateTime, ForeignKey
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column
import uuid
from datetime import datetime
from app.database.base import Base


class Invite(Base):
    __tablename__ = "invites"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    code: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    is_used: Mapped[bool] = mapped_column(Boolean, default=False)
    used_by_user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    used_at: Mapped[datetime] = mapped_column(DateTime, nullable=True)
    created_by_admin_device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")