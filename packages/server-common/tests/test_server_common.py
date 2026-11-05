import io
import json
import time
import uuid

import pytest
from fastapi import Depends
from fastapi.testclient import TestClient

from kuuma_contracts.testing import validate_body
from kuuma_server_common import (
    InMemoryDeviceKeys,
    PhoneOnlyEventError,
    SafeLogger,
    StreamConsumer,
    StreamPublisher,
    TopicOwnershipError,
    UnsafeLogValue,
    audit,
    case_auth,
    create_app,
    device_auth,
    issue_case_token,
    make_base,
    mount_openapi_stub,
)
from kuuma_server_common.pii_guard import find_personal_data
from kuuma_server_common.seed import seed
from kuuma_server_common.testing import MemoryStreams, signed_headers

NOW = "2026-11-05T09:30:00Z"


# ----------------------------------------------------------------------------- safe logging


def test_safe_logger_accepts_ids_enums_numbers():
    out = io.StringIO()
    SafeLogger("guardian", out).info(
        "alert.created", alert_id=str(uuid.uuid4()), severity="high", took_ms=1.5
    )
    rec = json.loads(out.getvalue())
    assert rec["event"] == "alert.created" and rec["severity"] == "high"


@pytest.mark.parametrize("value", ["you are stupid", "+256 772 123456", "Call me on 0772123456", {"x": 1}])
def test_safe_logger_refuses_content(value):
    with pytest.raises(UnsafeLogValue):
        SafeLogger("guardian", io.StringIO()).info("x.y", detail=value)


def test_safe_logger_redacts_in_production(monkeypatch):
    monkeypatch.setenv("KUUMA_ENV", "production")
    out = io.StringIO()
    SafeLogger("guardian", out).info("x.y", detail="free text here")
    assert json.loads(out.getvalue())["detail"] == "[refused]"


def test_audit_refuses_free_text():
    audit("share.approved", actor=str(uuid.uuid4()), resource=str(uuid.uuid4()))
    with pytest.raises(UnsafeLogValue):
        audit("share.approved", actor="Jane Doe")


# ----------------------------------------------------------------------------- app factory


def app_with_stub():
    app = create_app("guardian", api_prefixes=["/v1/guardian"])
    mount_openapi_stub(app, "guardian.yaml")
    return app


def test_health_metrics_and_request_id():
    c = TestClient(app_with_stub())
    assert c.get("/healthz").json() == {"status": "ok", "service": "guardian"}
    r = c.get("/v1/guardian/alerts", headers={"X-Request-ID": "req-abc-123"})
    assert r.headers["X-Request-ID"] == "req-abc-123"
    r2 = c.get("/v1/guardian/alerts", headers={"X-Request-ID": "not valid; drop table"})
    assert r2.headers["X-Request-ID"].startswith("req-") and "drop" not in r2.headers["X-Request-ID"]
    assert "kuuma_http_requests_total" in c.get("/metrics").text


def test_stub_serves_contract_valid_examples():
    c = TestClient(app_with_stub())
    r = c.get("/v1/guardian/alerts")
    assert r.status_code == 200
    validate_body("api/alert-list.json", r.json())


def test_stub_rejects_invalid_body_with_standard_error():
    c = TestClient(app_with_stub())
    r = c.post("/v1/guardian/alerts", json={"caseId": "nope"})
    assert r.status_code == 422
    validate_body("types/error-response.json", r.json())
    assert r.json()["error"]["code"] == "invalid_body"
    assert "nope" not in r.text  # never echo submitted values


def test_not_found_uses_standard_error():
    r = TestClient(app_with_stub()).get("/v1/guardian/nothing")
    assert r.status_code == 404
    validate_body("types/error-response.json", r.json())


# ----------------------------------------------------------------------------- personal-data guard


@pytest.mark.parametrize("text", ["+256 772 123456", "call 0772123456", "0772 123 456", "me@example.com"])
def test_finds_personal_data(text):
    assert find_personal_data(text)


@pytest.mark.parametrize(
    "text",
    [
        "2026-11-05T09:30:00Z",
        "c5bf1e54aa40f41226e9d2f6ab25a1c41aa5cb53e65f1f338fd6cd2d738d35a6",
        str(uuid.uuid4()),
        "[PHONE_1] said hi",
        "or1_dvEGCDHzlF6EirMup-k4lkULkPfjLpD3ZaXmOBRJsT4",
        "0.0.0",
        "version 12",
    ],
)
def test_no_false_positive(text):
    assert find_personal_data(text) is None


def test_guard_refuses_phone_number_in_body():
    c = TestClient(app_with_stub())
    r = c.post(
        "/v1/guardian/alerts",
        json={"caseId": seed()["sample_case"]["case_id"], "severity": "high", "note": "+256772123456"},
    )
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "personal_data_rejected"
    assert "772" not in r.text


# ----------------------------------------------------------------------------- auth


def device_app():
    keys = InMemoryDeviceKeys()
    dev = seed()["child_device"]
    keys.register(dev["device_id"], dev["public_key_der_b64"])
    app = create_app("guardian")

    @app.post("/v1/guardian/heartbeat")
    def hb(identity=Depends(device_auth(keys))):
        return {"device": identity.device_id}

    @app.post("/v1/cases/{caseId}/shares")
    def share(caseId: str, grant=Depends(case_auth())):  # noqa: N803
        return {"case": grant.case_id}

    return TestClient(app)


def test_device_signature_valid():
    body = b'{"deviceId":"x"}'
    r = device_app().post(
        "/v1/guardian/heartbeat",
        content=body,
        headers={
            **signed_headers("POST", "/v1/guardian/heartbeat", body),
            "content-type": "application/json",
        },
    )
    assert r.status_code == 200, r.text
    assert r.json()["device"] == seed()["child_device"]["device_id"]


def test_device_signature_tampered_body():
    headers = signed_headers("POST", "/v1/guardian/heartbeat", b'{"a":1}')
    r = device_app().post("/v1/guardian/heartbeat", content=b'{"a":2}', headers=headers)
    assert r.status_code == 401


def test_device_signature_replay_window():
    from kuuma_server_common.auth import sign_request
    from kuuma_server_common.seed import test_device_private_key

    old = sign_request(
        test_device_private_key(),
        "POST",
        "/v1/guardian/heartbeat",
        b"",
        seed()["child_device"]["device_id"],
        now=int(time.time()) - 3600,
    )
    assert device_app().post("/v1/guardian/heartbeat", headers=old).status_code == 401


def test_case_token():
    case_id = seed()["sample_case"]["case_id"]
    token = issue_case_token(case_id, seed()["child_device"]["device_id"])
    c = device_app()
    assert (
        c.post(f"/v1/cases/{case_id}/shares", headers={"Authorization": f"Bearer {token}"}).status_code == 200
    )
    other = str(uuid.uuid4())
    assert (
        c.post(f"/v1/cases/{other}/shares", headers={"Authorization": f"Bearer {token}"}).status_code == 403
    )
    assert c.post(f"/v1/cases/{case_id}/shares", headers={"Authorization": "Bearer x.y"}).status_code == 401


# ----------------------------------------------------------------------------- streams


def alert_event(**over):
    ev = {
        "eventId": str(uuid.uuid4()),
        "type": "guardian.alert.sent",
        "version": 1,
        "occurredAt": NOW,
        "deviceId": seed()["child_device"]["device_id"],
        "payload": {
            "alertId": str(uuid.uuid4()),
            "caseId": seed()["sample_case"]["case_id"],
            "severity": "high",
        },
    }
    ev.update(over)
    return ev


def test_stream_publisher_enforces_ownership():
    r = MemoryStreams()
    StreamPublisher(r, "guardian").publish(alert_event())
    assert len(r.events("guardian.alert.sent")) == 1
    with pytest.raises(TopicOwnershipError):
        StreamPublisher(r, "casefile").publish(alert_event())


def test_stream_publisher_refuses_phone_only():
    ev = {
        "eventId": str(uuid.uuid4()),
        "type": "sentinel.conversation.active",
        "version": 1,
        "occurredAt": NOW,
        "deviceId": str(uuid.uuid4()),
        "payload": {"conversationRef": "or1_" + "A" * 43, "appSource": "sms", "hasFlags": False},
    }
    with pytest.raises(PhoneOnlyEventError):
        StreamPublisher(MemoryStreams(), "sentinel").publish(ev)


def test_stream_publisher_validates():
    from kuuma_contracts.testing import ContractViolation

    bad = alert_event()
    bad["payload"]["severity"] = "extreme"
    with pytest.raises(ContractViolation):
        StreamPublisher(MemoryStreams(), "guardian").publish(bad)


def test_stream_consumer_roundtrip():
    r = MemoryStreams()
    consumer = StreamConsumer(r, "casefile", ["guardian.alert.sent"])
    StreamPublisher(r, "guardian").publish(alert_event())
    got = consumer.poll()
    assert [e["type"] for e in got] == ["guardian.alert.sent"]
    assert consumer.poll() == []


# ----------------------------------------------------------------------------- db


def test_make_base_pins_schema():
    from sqlalchemy import Column, Integer

    Base = make_base("guardian")

    class Thing(Base):
        __tablename__ = "things"
        id = Column(Integer, primary_key=True)

    assert Thing.__table__.schema == "guardian"
    with pytest.raises(ValueError):
        make_base("sentinel")  # Sentinel is phone-only: it has no cloud schema
