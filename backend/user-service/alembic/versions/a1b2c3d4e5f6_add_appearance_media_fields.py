"""add appearance media fields

Revision ID: a1b2c3d4e5f6
Revises: e0dcb814cbdf
Create Date: 2026-04-12 18:15:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, Sequence[str], None] = 'e0dcb814cbdf'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    op.add_column('user_settings', sa.Column('top_bar_media_id', sa.UUID(), nullable=True))
    op.add_column('user_settings', sa.Column('my_bubble_media_id', sa.UUID(), nullable=True))
    op.add_column('user_settings', sa.Column('their_bubble_media_id', sa.UUID(), nullable=True))

def downgrade() -> None:
    op.drop_column('user_settings', 'their_bubble_media_id')
    op.drop_column('user_settings', 'my_bubble_media_id')
    op.drop_column('user_settings', 'top_bar_media_id')
