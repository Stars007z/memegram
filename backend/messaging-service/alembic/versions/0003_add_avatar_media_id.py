"""Add avatar_media_id column to conversations table.

Allows group conversations to have an avatar image stored in item-storage-service.

Revision ID: 0003
Revises: 0002
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None

def upgrade() -> None:
    op.add_column(
        "conversations",
        sa.Column("avatar_media_id", UUID(as_uuid=True), nullable=True),
    )

def downgrade() -> None:
    op.drop_column("conversations", "avatar_media_id")
