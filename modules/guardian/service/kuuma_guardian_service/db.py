"""Guardian cloud tables, all in the `guardian` schema (rule 2: own your data).

Pairings, policies, alert summaries and approvals. No names, numbers or message text:
an alert's excerpt is ciphertext only the parent's phone can open.
"""

from __future__ import annotations

import datetime as dt

from sqlalchemy import Boolean, DateTime, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from kuuma_server_common.db import make_base

Base = make_base("guardian")


class Parent(Base):
    __tablename__ = "parents"
    parent_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    device_public_key: Mapped[str] = mapped_column(Text)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))


class Child(Base):
    __tablename__ = "children"
    child_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    parent_id: Mapped[str] = mapped_column(String(36), index=True)
    age_bracket: Mapped[str] = mapped_column(String(40))
    policy_json: Mapped[str] = mapped_column(Text)
    policy_version: Mapped[int] = mapped_column(Integer)
    paired: Mapped[bool] = mapped_column(Boolean, default=False)
    device_id: Mapped[str | None] = mapped_column(String(36), unique=True, nullable=True)
    device_public_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    last_heartbeat_at: Mapped[dt.datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    protection_active: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    missing_permissions: Mapped[str] = mapped_column(Text, default="[]")
    tamper_open: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))


class PairingCode(Base):
    __tablename__ = "pairing_codes"
    code: Mapped[str] = mapped_column(String(6), primary_key=True)
    child_id: Mapped[str] = mapped_column(String(36))
    expires_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))
    used: Mapped[bool] = mapped_column(Boolean, default=False)


class Alert(Base):
    __tablename__ = "alerts"
    alert_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    child_id: Mapped[str] = mapped_column(String(36), index=True)
    case_id: Mapped[str] = mapped_column(String(36))
    kind: Mapped[str] = mapped_column(String(20))
    severity: Mapped[str] = mapped_column(String(10))
    category: Mapped[str | None] = mapped_column(String(40), nullable=True)
    app_source: Mapped[str | None] = mapped_column(String(20), nullable=True)
    occurred_at: Mapped[str | None] = mapped_column(String(32), nullable=True)
    sealed_excerpt: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))
    read: Mapped[bool] = mapped_column(Boolean, default=False)


class Approval(Base):
    __tablename__ = "approvals"
    approval_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    request_id: Mapped[str] = mapped_column(String(36), unique=True)
    child_id: Mapped[str] = mapped_column(String(36), index=True)
    case_id: Mapped[str] = mapped_column(String(36))
    kind: Mapped[str] = mapped_column(String(20))
    recipient_type: Mapped[str | None] = mapped_column(String(20), nullable=True)
    status: Mapped[str] = mapped_column(String(20))
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))
    decided_at: Mapped[dt.datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    signature: Mapped[str | None] = mapped_column(String(43), nullable=True)
    delivered: Mapped[bool] = mapped_column(Boolean, default=False)


class Report(Base):
    """A parent's report of a case. The server never sees the case or the link: the child's
    phone builds the file, and the link comes back sealed with the family key."""

    __tablename__ = "reports"
    report_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    child_id: Mapped[str] = mapped_column(String(36), index=True)
    case_id: Mapped[str] = mapped_column(String(36))
    recipient_type: Mapped[str] = mapped_column(String(20))
    anonymous: Mapped[bool] = mapped_column(Boolean)
    signature: Mapped[str] = mapped_column(String(43))
    sealed_child_name: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(20))
    created_at: Mapped[dt.datetime] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[dt.datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    sealed_link: Mapped[str | None] = mapped_column(Text, nullable=True)
    delivered: Mapped[bool] = mapped_column(Boolean, default=False)
