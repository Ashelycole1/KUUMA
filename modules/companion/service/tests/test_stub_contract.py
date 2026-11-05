"""Every Companion endpoint answers with contract-valid sample data."""

import pytest
from fastapi.testclient import TestClient
from kuuma_companion_service.app import OPENAPI_FILES, app

from kuuma_contracts.testing import validate_body
from kuuma_server_common.openapi_stub import load_spec, operations, success_response

client = TestClient(app)
OPS = [(f, path, method, op) for f in OPENAPI_FILES for path, method, op in operations(load_spec(f))]


def fill(path: str, op: dict) -> str:
    for p in op.get("parameters", []):
        if "$ref" in p:
            continue
        path = path.replace("{" + p["name"] + "}", str(p["example"]))
    return path.replace("{caseId}", "c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a")


@pytest.mark.parametrize("file,path,method,op", OPS, ids=[o[3]["operationId"] for o in OPS])
def test_operation_returns_contract_valid_sample(file, path, method, op):
    req = ((op.get("requestBody") or {}).get("content") or {}).get("application/json")
    body = req["example"] if req else None
    r = client.request(method.upper(), fill(path, op), json=body)
    status, schema, _ = success_response(op)
    assert r.status_code == status, r.text
    if schema:
        validate_body(schema, r.json())


def test_health():
    assert client.get("/healthz").json()["service"] == "companion"
