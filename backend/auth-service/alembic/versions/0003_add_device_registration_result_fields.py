"""Add result fields to device_registration for polling

Revision ID: 0003_add_reg_result
Revises: 0002_fix_device_id
Create Date: 2026-03-29 00:00:00
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0003_add_reg_result"
down_revision: Union[str, Sequence[str], None] = "0002_fix_device_id"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column(
        "device_registration",
        sa.Column("confirmed_device_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "device_registration",
        sa.Column("result_access_token", sa.String(length=512), nullable=True),
    )
    op.add_column(
        "device_registration",
        sa.Column("result_refresh_token", sa.String(length=512), nullable=True),
    )
    op.add_column(
        "device_registration",
        sa.Column("result_token_expires_at", sa.DateTime(), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("device_registration", "result_token_expires_at")
    op.drop_column("device_registration", "result_refresh_token")
    op.drop_column("device_registration", "result_access_token")
    op.drop_column("device_registration", "confirmed_device_id")
