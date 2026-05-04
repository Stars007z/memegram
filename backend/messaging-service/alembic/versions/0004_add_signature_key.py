"""Add signature_key column to mls_key_packages.

Stores the public Ed25519 signature key extracted from the KeyPackage
on the client. Required so the server can include it in
device_revoked SSE events, which lets every group member call
removeMemberBySignatureKey for the revoked device's leaf node.

The signature key is PUBLIC: it is already embedded inside
key_package_data, visible to every group member through the MLS
RatchetTree, and stable for the lifetime of a device. Extracting it
into a dedicated column does not weaken privacy or anonymity.

Revision ID: 0004
Revises: 0003
"""

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "mls_key_packages",
        sa.Column("signature_key", sa.LargeBinary(), nullable=True),
    )
    op.create_index(
        "ix_key_packages_signature_key",
        "mls_key_packages",
        ["signature_key"],
    )


def downgrade() -> None:
    op.drop_index("ix_key_packages_signature_key", table_name="mls_key_packages")
    op.drop_column("mls_key_packages", "signature_key")
