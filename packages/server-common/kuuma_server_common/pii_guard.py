"""Refuses requests carrying phone numbers or email addresses.

No endpoint accepts names or phone numbers. Phones redact text before sending it
(Redactor in kuuma-core-android), so a number reaching a service is always a bug.
Names cannot be detected reliably by pattern; they are kept out by the contracts
(no name fields outside phone-only events) and by the Redactor on the phone.
"""

from __future__ import annotations

import json
import re

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from kuuma_server_common.errors import error_body
from kuuma_server_common.safe_log import get_logger

# International (+256 7XX XXX XXX), local Ugandan (07XX XXX XXX) and generic long digit runs.
PHONE = re.compile(r"(?<![\w/.-])(\+?\d[\d\s().-]{7,}\d)(?![\w/.-])")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
MAX_BODY = 256 * 1024


def _digits(s: str) -> int:
    return sum(c.isdigit() for c in s)


def find_personal_data(text: str) -> str | None:
    """Return the kind of personal data found ('phone_number' / 'email'), or None."""
    for m in PHONE.finditer(text):
        candidate = m.group(1)
        if 9 <= _digits(candidate) <= 15:
            return "phone_number"
    if EMAIL.search(text):
        return "email"
    return None


def _strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from _strings(v)
    elif isinstance(value, list):
        for v in value:
            yield from _strings(v)


def scan_json_body(raw: bytes) -> str | None:
    try:
        data = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        return None  # not JSON; FastAPI validation will reject it
    for s in _strings(data):
        kind = find_personal_data(s)
        if kind:
            return kind
    return None


class PersonalDataGuard:
    """ASGI middleware. Buffers JSON bodies (bounded) and refuses ones with personal data."""

    def __init__(self, app: ASGIApp, service: str):
        self.app = app
        self.log = get_logger(service)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope["method"] in ("GET", "HEAD", "OPTIONS"):
            query = scope.get("query_string", b"").decode("latin-1") if scope["type"] == "http" else ""
            if query and find_personal_data(query.replace("%2B", "+").replace("+", " ")):
                await self._refuse(send, "phone_number")
                return
            await self.app(scope, receive, send)
            return
        chunks, size, more = [], 0, True
        while more:
            msg = await receive()
            body = msg.get("body", b"")
            size += len(body)
            if size > MAX_BODY:
                await self._send_json(send, 413, error_body("payload_too_large", "Body too large"))
                return
            chunks.append(body)
            more = msg.get("more_body", False)
        raw = b"".join(chunks)
        kind = scan_json_body(raw) if raw else None
        if kind:
            self.log.warning("request.personal_data_refused", kind=kind)
            await self._refuse(send, kind)
            return

        sent = False

        async def replay() -> Message:
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": raw, "more_body": False}
            return await receive()

        await self.app(scope, replay, send)

    async def _refuse(self, send: Send, kind: str) -> None:
        await self._send_json(
            send,
            422,
            error_body(
                "personal_data_rejected",
                f"Request contains a {kind.replace('_', ' ')}; redact it on the phone first",
            ),
        )

    @staticmethod
    async def _send_json(send: Send, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        await send(
            {
                "type": "http.response.start",
                "status": status,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(data)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": data})
