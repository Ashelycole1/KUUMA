"""Per-request context (request id) shared by logging, errors and audit."""

from __future__ import annotations

import re
import secrets
from contextvars import ContextVar

_request_id: ContextVar[str | None] = ContextVar("kuuma_request_id", default=None)
VALID = re.compile(r"^req-[A-Za-z0-9-]{1,64}$")


def new_request_id() -> str:
    return "req-" + secrets.token_hex(8)


def accept_or_new(incoming: str | None) -> str:
    """Use the gateway's id if it is well-formed; otherwise mint one (never echo arbitrary input)."""
    return incoming if incoming and VALID.match(incoming) else new_request_id()


def set_request_id(rid: str | None):
    return _request_id.set(rid)


def reset_request_id(token) -> None:
    _request_id.reset(token)


def current_request_id() -> str | None:
    return _request_id.get()
