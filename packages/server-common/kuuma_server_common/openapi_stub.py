"""Serve an OpenAPI file's examples as a working stub API.

Skeleton services call `mount_openapi_stub(app, "guardian.yaml")`. Each operation then:
  * validates the request body against its contract schema (422 if invalid);
  * returns the operation's example with its documented status code.

Owners replace operations one at a time with real handlers:
    mount_openapi_stub(app, "guardian.yaml", skip={"listAlerts"})
    app.include_router(real_alerts_router)

`tests/test_stub_contract.py` in each service checks every stub response against the
schema, so sample data can never drift from the contract.
"""

from __future__ import annotations

import copy
from pathlib import Path
from typing import Any

import yaml
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from kuuma_contracts.testing import ContractViolation, validate_body
from kuuma_server_common.errors import KuumaError

CONTRACTS_OPENAPI = Path(__file__).resolve().parents[2] / "contracts" / "openapi"


def openapi_dir() -> Path:
    import os

    return Path(os.environ.get("KUUMA_OPENAPI_DIR", CONTRACTS_OPENAPI))


def load_spec(file_name: str) -> dict[str, Any]:
    return yaml.safe_load((openapi_dir() / file_name).read_text(encoding="utf-8"))


def schema_file(ref: str) -> str:
    """'../schemas/api/x.json' -> 'api/x.json' (a bundle-relative schema file)."""
    return ref.split("../schemas/", 1)[1]


def operations(spec: dict[str, Any]):
    for path, ops in spec["paths"].items():
        for method, op in ops.items():
            if method in ("get", "post", "put", "patch", "delete"):
                yield path, method, op


def success_response(op: dict[str, Any]) -> tuple[int, str | None, Any]:
    for status, resp in sorted(op["responses"].items()):
        if status.startswith("2"):
            media = (resp.get("content") or {}).get("application/json")
            if not media:
                return int(status), None, None
            return int(status), schema_file(media["schema"]["$ref"]), copy.deepcopy(media.get("example"))
    raise ValueError(f"{op['operationId']}: no 2xx response")


def mount_openapi_stub(
    app: FastAPI,
    file_name: str,
    *,
    skip: set[str] = frozenset(),
    dependencies: list | None = None,
    hooks: dict | None = None,
) -> list[str]:
    """Mount every operation in `file_name` (except `skip`). `hooks[operationId](app, body)` runs after
    validation, e.g. to publish a sample event on the bus."""
    hooks = hooks or {}
    spec = load_spec(file_name)
    mounted = []
    for path, method, op in operations(spec):
        op_id = op["operationId"]
        if op_id in skip:
            continue
        status, resp_schema, example = success_response(op)
        req = ((op.get("requestBody") or {}).get("content") or {}).get("application/json")
        req_schema = schema_file(req["schema"]["$ref"]) if req else None

        def make_handler(req_schema=req_schema, status=status, example=example, hook=hooks.get(op_id)):
            async def handler(request: Request):
                body = None
                if req_schema:
                    try:
                        body = await request.json()
                    except ValueError:
                        raise KuumaError(422, "invalid_body", "Body must be JSON") from None
                    try:
                        validate_body(req_schema, body)
                    except ContractViolation:
                        # Don't echo the validator message: it may quote submitted values.
                        raise KuumaError(422, "invalid_body", f"Body does not match {req_schema}") from None
                if hook:
                    hook(request.app, body)
                return JSONResponse(copy.deepcopy(example), status_code=status)

            return handler

        app.add_api_route(
            path,
            make_handler(),
            methods=[method.upper()],
            name=op_id,
            operation_id=op_id,
            summary=op.get("summary"),
            dependencies=dependencies or [],
            response_model=None,
            status_code=status,
        )
        mounted.append(op_id)
    return mounted
