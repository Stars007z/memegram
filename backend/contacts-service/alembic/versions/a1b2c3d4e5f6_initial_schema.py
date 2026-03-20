"""Initial schema

Revision ID: a1b2c3d4e5f6
Revises:
Create Date: 2026-03-20 00:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'contacts',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.Column('contact_user_id', sa.UUID(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.Column('is_favorite', sa.Boolean(), nullable=False, server_default='false'),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('user_id', 'contact_user_id', name='uq_contacts_user_contact'),
    )
    op.create_index('ix_contacts_user_id', 'contacts', ['user_id'])

    op.create_table(
        'blocked_users',
        sa.Column('id', sa.UUID(), nullable=False),
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.Column('blocked_user_id', sa.UUID(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('user_id', 'blocked_user_id', name='uq_blocked_users_pair'),
    )
    op.create_index('ix_blocked_users_user_id', 'blocked_users', ['user_id'])


def downgrade() -> None:
    op.drop_index('ix_blocked_users_user_id', table_name='blocked_users')
    op.drop_table('blocked_users')
    op.drop_index('ix_contacts_user_id', table_name='contacts')
    op.drop_table('contacts')
