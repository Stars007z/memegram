"""Replace non-unique index with unique constraint on mls_commit_messages(conversation_id, epoch).

Prevents two clients from both successfully committing at the same epoch
(TOCTOU race in commit_group_change).

Revision ID: 0002
Revises: 0001
"""

from alembic import op

revision = "0002"
down_revision = "0001_initial"
branch_labels = None
depends_on = None

def upgrade() -> None:
    op.drop_index("ix_commits_conv_epoch", table_name="mls_commit_messages")
    op.create_unique_constraint(
        "uq_commits_conv_epoch",
        "mls_commit_messages",
        ["conversation_id", "epoch"],
    )

def downgrade() -> None:
    op.drop_constraint("uq_commits_conv_epoch", "mls_commit_messages", type_="unique")
    op.create_index(
        "ix_commits_conv_epoch",
        "mls_commit_messages",
        ["conversation_id", "epoch"],
    )
