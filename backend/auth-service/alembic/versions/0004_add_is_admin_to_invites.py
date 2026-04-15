"""Add is_admin column to invites table

Revision ID: 0004_add_invite_is_admin
Revises: 0003_add_reg_result
Create Date: 2026-04-15 00:00:00
"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = "0004_add_invite_is_admin"
down_revision: Union[str, Sequence[str], None] = "0003_add_reg_result"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Add is_admin boolean column to invites, default False."""
    op.add_column(
        "invites",
        sa.Column("is_admin", sa.Boolean(), nullable=False, server_default=sa.text("false")),
    )


def downgrade() -> None:
    """Remove is_admin column from invites."""
    op.drop_column("invites", "is_admin")
