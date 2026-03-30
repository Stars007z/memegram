import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Integer, LargeBinary
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database.base import Base


class MlsGroup(Base):
    __tablename__ = "mls_groups"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True,
    )
    mls_group_id: Mapped[bytes] = mapped_column(LargeBinary, unique=True, nullable=False)
    current_epoch: Mapped[int] = mapped_column(BigInteger, default=0)
    cipher_suite: Mapped[int] = mapped_column(Integer, nullable=False)
    ratchet_tree: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.utcnow, onupdate=datetime.utcnow,
    )
