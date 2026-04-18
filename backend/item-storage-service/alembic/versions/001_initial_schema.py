"""initial schema

Revision ID: a1b2c3d4e5f6
Revises:
Create Date: 2026-04-11 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'storage_items',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('owner_user_id', sa.UUID(), nullable=False),
        sa.Column('item_type', sa.String(length=50), nullable=False),
        sa.Column('s3_bucket', sa.String(length=255), nullable=False),
        sa.Column('s3_key', sa.String(), nullable=False),
        sa.Column('mime_type', sa.String(length=100), nullable=False),
        sa.Column('size_bytes', sa.BigInteger(), nullable=False),
        sa.Column('access_policy', sa.String(length=20), nullable=False),
        sa.Column('status', sa.String(length=20), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('uploaded_at', sa.DateTime(), nullable=True),
        sa.Column('deleted_at', sa.DateTime(), nullable=True),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('s3_key'),
    )
    op.create_index(
        'ix_storage_items_owner_type',
        'storage_items',
        ['owner_user_id', 'item_type'],
    )
    op.create_index(
        'ix_storage_items_pending_status',
        'storage_items',
        ['status'],
        postgresql_where=sa.text("status = 'pending'"),
    )

def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index('ix_storage_items_pending_status', table_name='storage_items')
    op.drop_index('ix_storage_items_owner_type', table_name='storage_items')
    op.drop_table('storage_items')
