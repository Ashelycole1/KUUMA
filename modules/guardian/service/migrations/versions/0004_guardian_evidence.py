"""Guardian evidence: a parent asks to see a case's messages, the child decides.

Revision ID: guardian_0004
Revises: guardian_0003
"""

import sqlalchemy as sa
from alembic import op

revision = "guardian_0004"
down_revision = "guardian_0003"
branch_labels = None
depends_on = None
S = "guardian"
TS = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.create_table(
        "evidence_requests",
        sa.Column("request_id", sa.String(36), primary_key=True),
        sa.Column("child_id", sa.String(36), nullable=False, index=True),
        sa.Column("case_id", sa.String(36), nullable=False),
        sa.Column("signature", sa.String(43), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_at", TS, nullable=False),
        sa.Column("completed_at", TS),
        sa.Column("sealed_evidence", sa.Text),
        sa.Column("delivered", sa.Boolean, nullable=False),
        schema=S,
    )


def downgrade() -> None:
    op.drop_table("evidence_requests", schema=S)
