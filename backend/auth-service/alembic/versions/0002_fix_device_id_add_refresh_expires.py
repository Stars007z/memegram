"""Rename devices.device_id -> client_device_id; add sessions.refresh_expires_at

Revision ID: 0002_fix_device_id
Revises: 6c9192004552
Create Date: 2026-03-20 00:00:00
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = "0002_fix_device_id"
down_revision: Union[str, Sequence[str], None] = "6c9192004552"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # 1. Rename device_id → client_device_id in devices table
    op.alter_column("devices", "device_id", new_column_name="client_device_id")

    # 2. Add refresh_expires_at to sessions (nullable first, then populate, then not-null)
    op.add_column(
        "sessions",
        sa.Column("refresh_expires_at", sa.DateTime(), nullable=True),
    )
    # Backfill: set refresh_expires_at = expires_at + 6 days for existing rows
    op.execute(
        "UPDATE sessions SET refresh_expires_at = expires_at + INTERVAL '6 days' "
        "WHERE refresh_expires_at IS NULL"
    )
    op.alter_column("sessions", "refresh_expires_at", nullable=False)


def downgrade() -> None:
    op.alter_column("sessions", "refresh_expires_at", nullable=True)
    op.drop_column("sessions", "refresh_expires_at")
    op.alter_column("devices", "client_device_id", new_column_name="device_id")
