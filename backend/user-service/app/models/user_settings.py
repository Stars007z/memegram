import uuid
from datetime import datetime
from sqlalchemy import String, Boolean, Integer, DateTime, ForeignKey
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.database.base import Base


class UserSettings(Base):
    __tablename__ = "user_settings"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True, index=True
    )

    theme: Mapped[str] = mapped_column(String(20), default="system", nullable=False)
    language: Mapped[str] = mapped_column(String(10), default="en", nullable=False)
    is_translator_active: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    animations_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    account_auto_delete_after_days: Mapped[int] = mapped_column(Integer, nullable=True)

    profile_visible_to: Mapped[str] = mapped_column(String(20), default="everybody", nullable=False)
    last_active_visible_to: Mapped[str] = mapped_column(String(20), default="everybody", nullable=False)

    chat_background_media_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    top_bar_color: Mapped[str] = mapped_column(String(20), nullable=True)
    top_bar_media_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    my_bubble_media_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    their_bubble_media_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)

    ringtone_media_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    ringtone_vibration_strength: Mapped[int] = mapped_column(Integer, default=1, nullable=False)

    notification_sound: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=True)
    notification_vibration_strength: Mapped[int] = mapped_column(Integer, default=1, nullable=False)

    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)

    user: Mapped["User"] = relationship(back_populates="settings")
