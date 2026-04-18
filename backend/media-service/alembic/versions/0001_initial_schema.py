"""Initial schema — media_objects table

Revision ID: 0001_initial
Revises:
Create Date: 2026-03-27
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0001_initial"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    op.create_table(
        "media_objects",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("s3_bucket", sa.String(255), nullable=False),
        sa.Column("s3_key", sa.Text, unique=True, nullable=False),
        sa.Column("mime_type", sa.String(100), nullable=False),
        sa.Column("encrypted_size", sa.BigInteger, nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="pending"),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("uploaded_at", sa.DateTime, nullable=True),
        sa.Column("deleted_at", sa.DateTime, nullable=True),
        sa.Column("expires_at", sa.DateTime, nullable=True),
    )
    op.create_index(
        "ix_media_objects_expiry",
        "media_objects",
        ["status", "expires_at"],
        postgresql_where=sa.text("status = 'uploaded' AND expires_at IS NOT NULL"),
    )

def downgrade() -> None:
    op.drop_index("ix_media_objects_expiry", table_name="media_objects")
    op.drop_table("media_objects")
