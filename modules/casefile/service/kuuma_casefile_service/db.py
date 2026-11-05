"""Casefile cloud tables. Everything lives in the `casefile` schema (rule 2: own your data).

The cloud holds case IDs, hash roots, timestamps and encrypted, expiring bundles only:
no message text and no contact details, ever.
"""

from __future__ import annotations

import datetime as dt

from sqlalchemy import Boolean, DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from kuuma_server_common.db import make_base

Base = make_base("casefile")


class Timestamp(Base):
    """A hash root the mock (later: RFC 3161) authority has timestamped."""

    __tablename__ = "timestamps"
    hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    timestamp: Mapped[str] = mapped_column(String(32))
    signature: Mapped[str] = mapped_column(String(200))
    authority: Mapped[str] = mapped_column(String(80))


class Bundle(Base):
    """An encrypted case bundle in object storage. The server cannot decrypt it."""

    __tablename__ = "bundles"
    bundle_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    case_id: Mapped[str] = mapped_column(String(36), index=True)
    bundle_hash: Mapped[str] = mapped_column(String(64))
    item_count: Mapped[int] = mapped_column(Integer)
    size_bytes: Mapped[int] = mapped_column(Integer)
    object_key: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))


class Share(Base):
    __tablename__ = "shares"
    request_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    case_id: Mapped[str] = mapped_column(String(36), index=True)
    recipient_type: Mapped[str] = mapped_column(String(20))
    bundle_id: Mapped[str] = mapped_column(String(36))
    status: Mapped[str] = mapped_column(String(20))
    device_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))


class ChildDevice(Base):
    """Devices paired to a parent (from guardian.pairing.completed): their shares need approval."""

    __tablename__ = "child_devices"
    device_id: Mapped[str] = mapped_column(String(36), primary_key=True)


class ShareDecision(Base):
    """A parent's decision (from guardian.share.approved / declined), which may arrive before the share."""

    __tablename__ = "share_decisions"
    request_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    approved: Mapped[bool] = mapped_column(Boolean)
    decided_at: Mapped[str] = mapped_column(String(32))
