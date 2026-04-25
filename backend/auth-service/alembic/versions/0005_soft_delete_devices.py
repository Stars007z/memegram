"""Soft-delete auth devices by clearing client_device_id

Revision ID: 0005_soft_delete_devices
Revises: 0004_add_invite_is_admin
Create Date: 2026-04-25 00:00:00
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0005_soft_delete_devices"
down_revision: Union[str, Sequence[str], None] = "0004_add_invite_is_admin"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("deleted_at", sa.DateTime(), nullable=True))
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    indexes = {idx["name"] for idx in inspector.get_indexes("devices")}
    if "ix_devices_device_id" in indexes:
        op.drop_index("ix_devices_device_id", table_name="devices")
        indexes.remove("ix_devices_device_id")
    if "ix_devices_client_device_id" not in indexes:
        op.create_index("ix_devices_client_device_id", "devices", ["client_device_id"], unique=True)
    op.alter_column(
        "devices",
        "client_device_id",
        existing_type=sa.String(length=255),
        nullable=True,
    )


def downgrade() -> None:
    op.execute("DELETE FROM devices WHERE client_device_id IS NULL")
    op.alter_column(
        "devices",
        "client_device_id",
        existing_type=sa.String(length=255),
        nullable=False,
    )
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    indexes = {idx["name"] for idx in inspector.get_indexes("devices")}
    if "ix_devices_client_device_id" in indexes:
        op.drop_index("ix_devices_client_device_id", table_name="devices")
        indexes.remove("ix_devices_client_device_id")
    if "ix_devices_device_id" not in indexes:
        op.create_index("ix_devices_device_id", "devices", ["client_device_id"], unique=True)
    op.drop_column("devices", "deleted_at")
