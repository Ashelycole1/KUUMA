"""Wiring shared by every skeleton service: app + OpenAPI stubs + bus + database base."""

from __future__ import annotations

import datetime as dt
import os
import uuid
from collections.abc import Iterable

from fastapi import FastAPI

from kuuma_server_common.app import create_app
from kuuma_server_common.openapi_stub import load_spec, mount_openapi_stub
from kuuma_server_common.safe_log import get_logger
from kuuma_server_common.streams import StreamPublisher


def redis_client():
    """Real Redis when REDIS_URL is set (local stack), else in-memory streams (unit tests)."""
    url = os.environ.get("REDIS_URL")
    if url:
        import redis

        return redis.Redis.from_url(url)
    from kuuma_server_common.testing import MemoryStreams

    return MemoryStreams()


def now_utc() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def envelope(event_type: str, payload: dict, *, device_id: str, case_id: str | None = None) -> dict:
    ev = {
        "eventId": str(uuid.uuid4()),
        "type": event_type,
        "version": 1,
        "occurredAt": now_utc(),
        "deviceId": device_id,
        "payload": payload,
    }
    if case_id:
        ev["caseId"] = case_id
    return ev


def create_stub_service(
    build: str, openapi_files: Iterable[str], *, skip: set[str] = frozenset(), hooks: dict | None = None
) -> FastAPI:
    files = list(openapi_files)
    prefixes = sorted({"/".join(p.split("/")[:3]) for f in files for p in load_spec(f)["paths"]})
    app = create_app(build, api_prefixes=prefixes)
    app.state.bus = StreamPublisher(redis_client(), build)
    mounted = []
    for f in files:
        mounted += mount_openapi_stub(app, f, skip=skip, hooks=hooks)
    get_logger(build).info("service.started", stub_operations=len(mounted))
    return app
