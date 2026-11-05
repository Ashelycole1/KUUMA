"""Audit logging: who did what to which resource, with the same content-free rule.

    audit("share.approved", actor=parent_id, resource=case_id, outcome="ok")

Records go to the safe logger (stream "audit") and to any extra sinks (e.g. a table the
build owns). Actors and resources must be IDs; there is no free-text field.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from kuuma_server_common.safe_log import check_value, get_logger

_sinks: list[Callable[[dict[str, Any]], None]] = []


def add_audit_sink(sink: Callable[[dict[str, Any]], None]) -> None:
    _sinks.append(sink)


def audit(
    action: str,
    *,
    actor: str,
    resource: str | None = None,
    outcome: str = "ok",
    service: str = "audit",
    **fields: Any,
) -> dict[str, Any]:
    record = {
        "audit": True,
        "action": action,
        "actor": actor,
        "resource": resource,
        "outcome": outcome,
        **fields,
    }
    for k, v in record.items():
        check_value(k, v)  # raises on anything content-like, whatever the environment
    get_logger(service).info("audit." + action, **{k: v for k, v in record.items() if k != "action"})
    for sink in _sinks:
        sink(record)
    return record
