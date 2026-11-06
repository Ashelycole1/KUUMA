"""Guardian reports: a parent reports a case, the child's phone builds it.

Revision ID: guardian_0003
Revises: guardian_0002
"""

import sqlalchemy as sa
from alembic import op

revision = "guardian_0003"
down_revision = "guardian_0002"
branch_labels = None
depends_on = None
S = "guardian"
TS = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.create_table(
        "reports",
        sa.Column("report_id", sa.String(36), primary_key=True),
        sa.Column("child_id", sa.String(36), nullable=False, index=True),
        sa.Column("case_id", sa.String(36), nullable=False),
        sa.Column("recipient_type", sa.String(20), nullable=False),
        sa.Column("anonymous", sa.Boolean, nullable=False),
        sa.Column("signature", sa.String(43), nullable=False),
        sa.Column("sealed_child_name", sa.Text),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("completed_at", TS),
        sa.Column("sealed_link", sa.Text),
        sa.Column("delivered", sa.Boolean, nullable=False),
        schema=S,
    )


def downgrade() -> None:
    op.drop_table("reports", schema=S)
