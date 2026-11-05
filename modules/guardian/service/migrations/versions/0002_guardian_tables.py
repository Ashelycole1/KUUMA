"""Guardian tables: parents, children, pairing codes, alerts, approvals.

Revision ID: guardian_0002
Revises: guardian_0001
"""

import sqlalchemy as sa
from alembic import op

revision = "guardian_0002"
down_revision = "guardian_0001"
branch_labels = None
depends_on = None
S = "guardian"
TS = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.create_table(
        "parents",
        sa.Column("parent_id", sa.String(36), primary_key=True),
        sa.Column("token_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("device_public_key", sa.Text, nullable=False),
        sa.Column("created_at", TS, nullable=False),
        schema=S,
    )
    op.create_table(
        "children",
        sa.Column("child_id", sa.String(36), primary_key=True),
        sa.Column("parent_id", sa.String(36), nullable=False, index=True),
        sa.Column("age_bracket", sa.String(40), nullable=False),
        sa.Column("policy_json", sa.Text, nullable=False),
        sa.Column("policy_version", sa.Integer, nullable=False),
        sa.Column("paired", sa.Boolean, nullable=False),
        sa.Column("device_id", sa.String(36), unique=True),
        sa.Column("device_public_key", sa.Text),
        sa.Column("last_heartbeat_at", TS),
        sa.Column("protection_active", sa.Boolean),
        sa.Column("missing_permissions", sa.Text, nullable=False),
        sa.Column("tamper_open", sa.Boolean, nullable=False),
        sa.Column("created_at", TS, nullable=False),
        schema=S,
    )
    op.create_table(
        "pairing_codes",
        sa.Column("code", sa.String(6), primary_key=True),
        sa.Column("child_id", sa.String(36), nullable=False),
        sa.Column("expires_at", TS, nullable=False),
        sa.Column("used", sa.Boolean, nullable=False),
        schema=S,
    )
    op.create_table(
        "alerts",
        sa.Column("alert_id", sa.String(36), primary_key=True),
        sa.Column("child_id", sa.String(36), nullable=False, index=True),
        sa.Column("case_id", sa.String(36), nullable=False),
        sa.Column("kind", sa.String(20), nullable=False),
        sa.Column("severity", sa.String(10), nullable=False),
        sa.Column("category", sa.String(40)),
        sa.Column("app_source", sa.String(20)),
        sa.Column("occurred_at", sa.String(32)),
        sa.Column("sealed_excerpt", sa.Text),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("read", sa.Boolean, nullable=False),
        schema=S,
    )
    op.create_table(
        "approvals",
        sa.Column("approval_id", sa.String(36), primary_key=True),
        sa.Column("request_id", sa.String(36), nullable=False, unique=True),
        sa.Column("child_id", sa.String(36), nullable=False, index=True),
        sa.Column("case_id", sa.String(36), nullable=False),
        sa.Column("kind", sa.String(20), nullable=False),
        sa.Column("recipient_type", sa.String(20)),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("decided_at", TS),
        sa.Column("signature", sa.String(43)),
        sa.Column("delivered", sa.Boolean, nullable=False),
        schema=S,
    )


def downgrade() -> None:
    for t in ("approvals", "alerts", "pairing_codes", "children", "parents"):
        op.drop_table(t, schema=S)
