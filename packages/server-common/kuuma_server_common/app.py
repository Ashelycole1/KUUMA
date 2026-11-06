"""FastAPI app factory used by every Nilinde service.

    app = create_app("guardian", api_prefixes=["/v1/guardian"])
    app.include_router(my_router)

Gives every service the same: error format, request ids, the personal-data guard,
content-free access logs, /healthz, /readyz and /metrics.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Iterable

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, Histogram, generate_latest

from kuuma_server_common.errors import install_error_handlers
from kuuma_server_common.pii_guard import PersonalDataGuard
from kuuma_server_common.request_context import accept_or_new, reset_request_id, set_request_id
from kuuma_server_common.safe_log import get_logger


def create_app(
    service: str,
    *,
    api_prefixes: Iterable[str] = (),
    version: str = "0.1.0",
    ready_check: Callable[[], bool] | None = None,
) -> FastAPI:
    log = get_logger(service)
    app = FastAPI(title=f"kuuma-{service}", version=version, docs_url="/docs", redoc_url=None)
    app.state.service = service
    app.state.api_prefixes = tuple(api_prefixes)
    install_error_handlers(app, service)

    registry = CollectorRegistry()
    requests = Counter(
        "kuuma_http_requests_total",
        "HTTP requests",
        ["service", "route", "method", "status"],
        registry=registry,
    )
    latency = Histogram(
        "kuuma_http_request_seconds", "HTTP latency", ["service", "route", "method"], registry=registry
    )
    app.state.metrics_registry = registry

    # Added first so it runs inside request_context (which is outermost) and its refusals get a request id.
    app.add_middleware(PersonalDataGuard, service=service)

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        rid = accept_or_new(request.headers.get("x-request-id"))
        token = set_request_id(rid)
        start = time.perf_counter()
        try:
            response: Response = await call_next(request)
            # Route templates only (/v1/cases/{caseId}/shares), never raw paths with ids or hashes.
            route = getattr(request.scope.get("route"), "path", "unmatched")
            took = time.perf_counter() - start
            requests.labels(service, route, request.method, str(response.status_code)).inc()
            latency.labels(service, route, request.method).observe(took)
            response.headers["X-Request-ID"] = rid
            if route not in ("/healthz", "/readyz", "/metrics"):
                log.info(
                    "http.request",
                    route=route,
                    method=request.method.lower(),
                    status=response.status_code,
                    took_ms=round(took * 1000, 2),
                )
            return response
        finally:
            reset_request_id(token)

    @app.get("/healthz", include_in_schema=False)
    def healthz():
        return {"status": "ok", "service": service}

    @app.get("/readyz", include_in_schema=False)
    def readyz(response: Response):
        ok = ready_check() if ready_check else True
        response.status_code = 200 if ok else 503
        return {"status": "ready" if ok else "not_ready", "service": service}

    @app.get("/metrics", include_in_schema=False)
    def metrics():
        return Response(generate_latest(registry), media_type=CONTENT_TYPE_LATEST)

    return app
