"""Casefile tables: timestamps, bundles, shares, child devices, share decisions.

Revision ID: casefile_0002
Revises: casefile_0001
"""

import sqlalchemy as sa
from alembic import op

revision = "casefile_0002"
down_revision = "casefile_0001"
branch_labels = None
depends_on = None
S = "casefile"


def upgrade() -> None:
    op.create_table(
        "timestamps",
        sa.Column("hash", sa.String(64), primary_key=True),
        sa.Column("timestamp", sa.String(32), nullable=False),
        sa.Column("signature", sa.String(200), nullable=False),
        sa.Column("authority", sa.String(80), nullable=False),
        schema=S,
    )
    op.create_table(
        "bundles",
        sa.Column("bundle_id", sa.String(36), primary_key=True),
        sa.Column("case_id", sa.String(36), nullable=False, index=True),
        sa.Column("bundle_hash", sa.String(64), nullable=False),
        sa.Column("item_count", sa.Integer, nullable=False),
        sa.Column("size_bytes", sa.Integer, nullable=False),
        sa.Column("object_key", sa.String(200), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        schema=S,
    )
    op.create_table(
        "shares",
        sa.Column("request_id", sa.String(36), primary_key=True),
        sa.Column("case_id", sa.String(36), nullable=False, index=True),
        sa.Column("recipient_type", sa.String(20), nullable=False),
        sa.Column("bundle_id", sa.String(36), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("device_id", sa.String(36), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        schema=S,
    )
    op.create_table("child_devices", sa.Column("device_id", sa.String(36), primary_key=True), schema=S)
    op.create_table(
        "share_decisions",
        sa.Column("request_id", sa.String(36), primary_key=True),
        sa.Column("approved", sa.Boolean, nullable=False),
        sa.Column("decided_at", sa.String(32), nullable=False),
        schema=S,
    )


def downgrade() -> None:
    for t in ("share_decisions", "child_devices", "shares", "bundles", "timestamps"):
        op.drop_table(t, schema=S)
