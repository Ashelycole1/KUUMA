"""Casefile database: every table lives in the `casefile` schema (rule 2: own your data)."""

from kuuma_server_common.db import make_base

Base = make_base("casefile")

# Add models here, e.g.
# class Thing(Base):
#     __tablename__ = "things"
#     id: Mapped[uuid.UUID] = mapped_column(primary_key=True)
