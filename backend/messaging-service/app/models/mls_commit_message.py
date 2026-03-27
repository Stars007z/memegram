import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Index, LargeBinary
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database.base import Base


class MlsCommitMessage(Base):
    __tablename__ = "mls_commit_messages"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4,
    )
    conversation_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    sender_device_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    epoch: Mapped[int] = mapped_column(BigInteger, nullable=False)
    commit_data: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    __table_args__ = (
        Index("ix_commits_conv_epoch", "conversation_id", "epoch"),
    )
