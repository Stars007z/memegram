"""drop notification_sound and ringtone fields

Revision ID: b2c3d4e5f6a7
Revises: a1b2c3d4e5f6
Create Date: 2026-04-24 12:00:00.000000

Removes notification_sound, notification_vibration_strength, ringtone_media_id,
ringtone_vibration_strength columns from user_settings. The product no longer
exposes a sound chooser and there are no calls.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'b2c3d4e5f6a7'
down_revision: Union[str, Sequence[str], None] = 'a1b2c3d4e5f6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.drop_column('user_settings', 'notification_vibration_strength')
    op.drop_column('user_settings', 'notification_sound')
    op.drop_column('user_settings', 'ringtone_vibration_strength')
    op.drop_column('user_settings', 'ringtone_media_id')


def downgrade() -> None:
    op.add_column(
        'user_settings',
        sa.Column('ringtone_media_id', sa.UUID(), nullable=True),
    )
    op.add_column(
        'user_settings',
        sa.Column(
            'ringtone_vibration_strength',
            sa.Integer(),
            nullable=False,
            server_default='1',
        ),
    )
    op.add_column(
        'user_settings',
        sa.Column('notification_sound', sa.UUID(), nullable=True),
    )
    op.add_column(
        'user_settings',
        sa.Column(
            'notification_vibration_strength',
            sa.Integer(),
            nullable=False,
            server_default='1',
        ),
    )
