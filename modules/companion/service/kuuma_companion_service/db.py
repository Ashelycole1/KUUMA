"""Companion database: every table lives in the `companion` schema (rule 2: own your data)."""

from kuuma_server_common.db import make_base

Base = make_base("companion")

# Add models here, e.g.
# class Thing(Base):
#     __tablename__ = "things"
#     id: Mapped[uuid.UUID] = mapped_column(primary_key=True)
