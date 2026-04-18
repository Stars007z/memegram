"""Initial schema — all messaging tables

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
        "conversations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("type", sa.String(20), nullable=False),
        sa.Column("name", sa.String(255), nullable=True),
        sa.Column("created_by_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("last_message_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("last_activity_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
    )
    op.create_index(
        "ix_conversations_last_activity",
        "conversations",
        [sa.text("last_activity_at DESC")],
    )

    op.create_table(
        "conversation_members",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "conversation_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("conversations.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("role", sa.String(20), nullable=False, server_default="member"),
        sa.Column("joined_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("left_at", sa.DateTime, nullable=True),
        sa.Column("last_read_message_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.UniqueConstraint("conversation_id", "user_id", name="uq_conv_member"),
    )
    op.create_index("ix_conv_members_user_id", "conversation_members", ["user_id"])

    op.create_table(
        "messages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "conversation_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("conversations.id"),
            nullable=False,
        ),
        sa.Column("sender_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sender_device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("type", sa.String(20), nullable=False),
        sa.Column("mls_ciphertext", sa.LargeBinary, nullable=False),
        sa.Column("mls_epoch", sa.BigInteger, nullable=True),
        sa.Column("media_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("reply_to_message_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column(
            "client_message_id",
            postgresql.UUID(as_uuid=True),
            unique=True,
            nullable=False,
        ),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("edited_at", sa.DateTime, nullable=True),
        sa.Column("deleted_at", sa.DateTime, nullable=True),
    )
    op.create_index(
        "ix_messages_conv_created",
        "messages",
        ["conversation_id", sa.text("created_at DESC")],
    )
    op.create_index("ix_messages_sender", "messages", ["sender_user_id"])

    op.create_table(
        "media_attachments",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("uploader_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("conversation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("s3_key", sa.Text, nullable=True),
        sa.Column("mime_type", sa.String(100), nullable=False),
        sa.Column("encrypted_size", sa.BigInteger, nullable=False),
        sa.Column("encryption_metadata", sa.LargeBinary, nullable=False),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("confirmed_at", sa.DateTime, nullable=True),
        sa.Column("expires_at", sa.DateTime, nullable=True),
    )

    op.create_table(
        "mls_groups",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("mls_group_id", sa.LargeBinary, unique=True, nullable=False),
        sa.Column("current_epoch", sa.BigInteger, server_default="0"),
        sa.Column("cipher_suite", sa.Integer, nullable=False),
        sa.Column("ratchet_tree", sa.LargeBinary, nullable=True),
        sa.Column("updated_at", sa.DateTime, server_default=sa.func.now()),
    )

    op.create_table(
        "mls_key_packages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("key_package_data", sa.LargeBinary, nullable=False),
        sa.Column("key_package_ref", sa.LargeBinary, unique=True, nullable=False),
        sa.Column("cipher_suite", sa.Integer, nullable=False),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("consumed_at", sa.DateTime, nullable=True),
    )
    op.create_index(
        "ix_key_packages_available",
        "mls_key_packages",
        ["user_id", "device_id"],
        postgresql_where=sa.text("consumed_at IS NULL"),
    )

    op.create_table(
        "mls_welcome_messages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("recipient_device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("conversation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("welcome_data", sa.LargeBinary, nullable=False),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
        sa.Column("delivered_at", sa.DateTime, nullable=True),
    )

    op.create_table(
        "mls_commit_messages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("conversation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sender_device_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("epoch", sa.BigInteger, nullable=False),
        sa.Column("commit_data", sa.LargeBinary, nullable=False),
        sa.Column("created_at", sa.DateTime, server_default=sa.func.now()),
    )
    op.create_index(
        "ix_commits_conv_epoch",
        "mls_commit_messages",
        ["conversation_id", "epoch"],
    )

def downgrade() -> None:
    op.drop_table("mls_commit_messages")
    op.drop_table("mls_welcome_messages")
    op.drop_table("mls_key_packages")
    op.drop_table("mls_groups")
    op.drop_table("media_attachments")
    op.drop_table("messages")
    op.drop_table("conversation_members")
    op.drop_table("conversations")
