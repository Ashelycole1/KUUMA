"""The standard error format: {"error": {"code", "message", "requestId"}} (contract ErrorResponse)."""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from kuuma_server_common.request_context import current_request_id
from kuuma_server_common.safe_log import get_logger

STATUS_CODES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "conflict",
    413: "payload_too_large",
    422: "invalid_body",
    429: "rate_limited",
    500: "internal",
    503: "unavailable",
}


class KuumaError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def error_body(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message, "requestId": current_request_id() or "req-none"}}


def install_error_handlers(app: FastAPI, service: str) -> None:
    log = get_logger(service)

    @app.exception_handler(KuumaError)
    async def _kuuma(_: Request, exc: KuumaError):
        return JSONResponse(error_body(exc.code, exc.message), status_code=exc.status)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException):
        code = STATUS_CODES.get(exc.status_code, "error")
        # Never echo `detail` unless we set it ourselves as a plain developer message.
        message = exc.detail if isinstance(exc.detail, str) and len(exc.detail) < 200 else code
        return JSONResponse(error_body(code, message), status_code=exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError):
        # Report field locations only, never the submitted values (they may hold content).
        locs = sorted({".".join(str(p) for p in e.get("loc", ())) for e in exc.errors()})
        return JSONResponse(
            error_body("invalid_body", "Invalid fields: " + ", ".join(locs)[:300]), status_code=422
        )

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception):
        log.error("request.unhandled_error", error_type=type(exc).__name__.lower())
        return JSONResponse(error_body("internal", "Internal error"), status_code=500)
