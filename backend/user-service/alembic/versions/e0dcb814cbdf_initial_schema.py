"""initial schema

Revision ID: e0dcb814cbdf
Revises:
Create Date: 2026-03-18 21:04:31.037325

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'e0dcb814cbdf'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    """Upgrade schema."""

    op.create_table('users',
    sa.Column('id', sa.UUID(), nullable=False),
    sa.Column('username', sa.String(length=255), nullable=False),
    sa.Column('avatar_media_id', sa.UUID(), nullable=True),
    sa.Column('profile_background_media_id', sa.UUID(), nullable=True),
    sa.Column('user_public_key', sa.String(length=2048), nullable=True),
    sa.Column('bio', sa.String(length=1024), nullable=True),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.Column('last_active', sa.DateTime(), nullable=True),
    sa.Column('is_deleted', sa.Boolean(), nullable=False),
    sa.Column('deleted_at', sa.DateTime(), nullable=True),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_users_username'), 'users', ['username'], unique=False)
    op.create_table('user_settings',
    sa.Column('id', sa.UUID(), nullable=False),
    sa.Column('user_id', sa.UUID(), nullable=False),
    sa.Column('theme', sa.String(length=20), nullable=False),
    sa.Column('language', sa.String(length=10), nullable=False),
    sa.Column('is_translator_active', sa.Boolean(), nullable=False),
    sa.Column('animations_enabled', sa.Boolean(), nullable=False),
    sa.Column('account_auto_delete_after_days', sa.Integer(), nullable=True),
    sa.Column('profile_visible_to', sa.String(length=20), nullable=False),
    sa.Column('last_active_visible_to', sa.String(length=20), nullable=False),
    sa.Column('chat_background_media_id', sa.UUID(), nullable=True),
    sa.Column('top_bar_color', sa.String(length=20), nullable=True),
    sa.Column('ringtone_media_id', sa.UUID(), nullable=True),
    sa.Column('ringtone_vibration_strength', sa.Integer(), nullable=False),
    sa.Column('notification_sound', sa.UUID(), nullable=True),
    sa.Column('notification_vibration_strength', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.Column('updated_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_user_settings_user_id'), 'user_settings', ['user_id'], unique=True)

def downgrade() -> None:
    """Downgrade schema."""

    op.drop_index(op.f('ix_user_settings_user_id'), table_name='user_settings')
    op.drop_table('user_settings')
    op.drop_index(op.f('ix_users_username'), table_name='users')
    op.drop_table('users')

