"""create device_push_tokens and notification_log tables

Revision ID: 001
Revises:
Create Date: 2026-04-15

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision: str = "001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "device_push_tokens",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", UUID(as_uuid=True), nullable=False, unique=True),
        sa.Column("platform", sa.String(10), nullable=False),
        sa.Column("push_token", sa.Text, nullable=False),
        sa.Column("is_active", sa.Boolean, server_default=sa.text("true")),
        sa.Column("created_at", sa.DateTime, nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime, nullable=False, server_default=sa.func.now()),
        sa.Column("last_success_at", sa.DateTime, nullable=True),
        sa.Column("consecutive_failures", sa.Integer, server_default=sa.text("0")),
    )

    op.create_index(
        "ix_device_push_tokens_user_active",
        "device_push_tokens",
        ["user_id"],
        postgresql_where=sa.text("is_active = true"),
    )
    op.create_index(
        "ix_device_push_tokens_push_token",
        "device_push_tokens",
        ["push_token"],
    )

    op.create_table(
        "notification_log",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("event_type", sa.String(50), nullable=False),
        sa.Column("conversation_id", UUID(as_uuid=True), nullable=False),
        sa.Column("recipient_user_id", UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", UUID(as_uuid=True), nullable=True),
        sa.Column("platform", sa.String(10), nullable=True),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("error_code", sa.String(100), nullable=True),
        sa.Column("attempts", sa.Integer, server_default=sa.text("1")),
        sa.Column("created_at", sa.DateTime, nullable=False, server_default=sa.func.now()),
    )


def downgrade() -> None:
    op.drop_table("notification_log")
    op.drop_index("ix_device_push_tokens_push_token", table_name="device_push_tokens")
    op.drop_index("ix_device_push_tokens_user_active", table_name="device_push_tokens")
    op.drop_table("device_push_tokens")
