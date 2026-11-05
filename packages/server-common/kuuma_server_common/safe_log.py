"""Content-free logging (rule 7: never log message content).

Logs are JSON lines holding only an event name plus IDs, enums, numbers, booleans and
timings. Anything else is refused: in dev/test it raises, so the mistake is caught by
tests; in production the value is replaced with "[refused]" so a bad log line never
takes a service down.

    log = get_logger("guardian")
    log.info("alert.created", alert_id=alert.alert_id, severity="high", took_ms=12.5)
"""

from __future__ import annotations

import enum
import json
import os
import re
import sys
import time
import uuid
from typing import IO, Any

from kuuma_server_common.request_context import current_request_id

# An event name or a token value: lowercase, no spaces, short. e.g. "alert.created", "high".
TOKEN = re.compile(r"^[a-z0-9][a-z0-9_.:/-]{0,79}$")
# IDs we generate: UUIDs, opaque refs, request ids, hashes.
ID = re.compile(
    r"^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|or1_[A-Za-z0-9_-]{43}|req-[A-Za-z0-9-]{1,64}|[0-9a-f]{64})$"
)
# Paths are allowed only as route templates (no raw path parameters), e.g. /v1/cases/{caseId}/shares
ROUTE = re.compile(r"^/[A-Za-z0-9_{}/.-]{0,200}$")


class UnsafeLogValue(ValueError):
    pass


def _strict() -> bool:
    return os.environ.get("KUUMA_ENV", "dev") != "production"


def check_value(key: str, value: Any) -> Any:
    """Return a JSON-safe value, or raise UnsafeLogValue if it could carry content."""
    if value is None or isinstance(value, bool | int | float):
        return value
    if isinstance(value, enum.Enum):
        return check_value(key, value.value)
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, str):
        if TOKEN.match(value) or ID.match(value):
            return value
        if key in ("route", "path_template") and ROUTE.match(value):
            return value
        raise UnsafeLogValue(f"log field {key!r}: free text is not allowed in logs")
    if isinstance(value, list | tuple):
        return [check_value(key, v) for v in value]
    raise UnsafeLogValue(f"log field {key!r}: type {type(value).__name__} is not allowed in logs")


class SafeLogger:
    def __init__(self, service: str, stream: IO[str] | None = None):
        if not TOKEN.match(service):
            raise UnsafeLogValue("service name must be a token")
        self.service = service
        self.stream = stream

    def _emit(self, level: str, event: str, fields: dict[str, Any]) -> None:
        if not TOKEN.match(event):
            raise UnsafeLogValue(f"log event name {event!r} must be a lowercase token like 'alert.created'")
        record: dict[str, Any] = {
            "ts": round(time.time(), 3),
            "level": level,
            "service": self.service,
            "event": event,
        }
        rid = current_request_id()
        if rid:
            record["request_id"] = rid
        for k, v in fields.items():
            if not re.match(r"^[a-z][a-z0-9_]{0,39}$", k):
                raise UnsafeLogValue(f"log field name {k!r} must be snake_case")
            try:
                record[k] = check_value(k, v)
            except UnsafeLogValue:
                if _strict():
                    raise
                record[k] = "[refused]"
        line = json.dumps(record, separators=(",", ":")) + "\n"
        out = self.stream or sys.stdout
        out.write(line)
        out.flush()
        # CI sets KUUMA_LOG_FILE so the privacy scan can inspect every log line tests produced.
        copy_to = os.environ.get("KUUMA_LOG_FILE")
        if copy_to:
            os.makedirs(os.path.dirname(copy_to) or ".", exist_ok=True)
            with open(copy_to, "a", encoding="utf-8") as f:
                f.write(line)

    def debug(self, event: str, **fields: Any) -> None:
        if os.environ.get("KUUMA_LOG_DEBUG") == "1":
            self._emit("debug", event, fields)

    def info(self, event: str, **fields: Any) -> None:
        self._emit("info", event, fields)

    def warning(self, event: str, **fields: Any) -> None:
        self._emit("warning", event, fields)

    def error(self, event: str, **fields: Any) -> None:
        self._emit("error", event, fields)


_loggers: dict[str, SafeLogger] = {}


def get_logger(service: str) -> SafeLogger:
    if service not in _loggers:
        _loggers[service] = SafeLogger(service)
    return _loggers[service]
