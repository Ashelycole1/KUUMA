"""The Guardian service end to end: parent, pairing, policy, alerts, approvals, heartbeat, tamper."""

import base64
import datetime as dt
import json
import uuid

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from kuuma_guardian_service.app import TAMPER_AFTER, RecordingPush, build_app, sqlite_engine

from kuuma_contracts.testing import assert_publishes, validate_body
from kuuma_server_common.auth import sign_request
from kuuma_server_common.testing import MemoryStreams

SIG = "wIb_NLyPXQ2pLw0INOBonUMMH7KFQumUTf8WGWpB93A"
CASE = "c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a"
EXCERPT = "2xe1KPnJyC1COJEUWY1q7PMR8pG4e7wkcCRg_4RNW-jsl5QnWXGDXUA3vFN_0DzBfG_IQYwq2mzRDNSs"


def policy(version=1, **over):
    return {
        "ageBracket": "age_13_15",
        "watchedApps": ["whatsapp", "sms"],
        "thresholds": {
            "flagMinConfidence": 0.6,
            "alertMinSeverity": "medium",
            "captureMinSeverity": "medium",
        },
        "policyVersion": version,
        "signature": SIG,
        **over,
    }


def public_der(key) -> str:
    return base64.b64encode(
        key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    ).decode()


class Clock:
    def __init__(self):
        self.now = dt.datetime.now(dt.UTC)

    def __call__(self):
        return self.now


class Phone:
    """A child phone: signs every request with its own key."""

    def __init__(self, client):
        self.client, self.key, self.device_id = client, ec.generate_private_key(ec.SECP256R1()), None

    def post(self, path, payload):
        raw = json.dumps(payload).encode()
        headers = sign_request(self.key, "POST", path, raw, self.device_id) if self.device_id else {}
        return self.client.post(path, content=raw, headers={**headers, "content-type": "application/json"})


@pytest.fixture
def env():
    redis, push, clock = MemoryStreams(), RecordingPush(), Clock()
    app = build_app(engine=sqlite_engine(), redis=redis, push=push, clock=clock)
    client = TestClient(app)
    parent_key = ec.generate_private_key(ec.SECP256R1())
    r = client.post("/v1/guardian/parents", json={"devicePublicKey": public_der(parent_key)})
    assert r.status_code == 201
    validate_body("api/parent-registration-response.json", r.json())
    auth = {"Authorization": f"Bearer {r.json()['sessionToken']}"}
    return client, app, redis, push, clock, auth


def pair(client, auth):
    code = client.post("/v1/guardian/pairing-codes", json={"policy": policy()}, headers=auth)
    assert code.status_code == 201, code.text
    validate_body("api/pairing-code-response.json", code.json())
    phone = Phone(client)
    r = phone.post(
        "/v1/guardian/pairings",
        {
            "pairingCode": code.json()["pairingCode"],
            "devicePublicKey": public_der(phone.key),
            "ageBracket": "age_13_15",
        },
    )
    assert r.status_code == 201, r.text
    validate_body("api/pairing-response.json", r.json())
    phone.device_id = r.json()["deviceId"]
    return phone, r.json()["childId"]


def test_pairing_is_one_time_and_publishes(env):
    client, app, redis, _, _, auth = env
    code = client.post("/v1/guardian/pairing-codes", json={"policy": policy()}, headers=auth).json()[
        "pairingCode"
    ]
    phone = Phone(client)
    payload = {
        "pairingCode": code,
        "devicePublicKey": public_der(phone.key),
        "ageBracket": "age_13_15",
    }
    assert phone.post("/v1/guardian/pairings", payload).status_code == 201
    assert phone.post("/v1/guardian/pairings", payload).status_code == 404  # used
    paired = redis.events("guardian.pairing.completed")
    assert len(paired) == 1
    assert_publishes("guardian", paired[0])
    children = client.get("/v1/guardian/children", headers=auth).json()
    validate_body("api/child-list.json", children)
    assert children["children"][0]["paired"] is True


def test_unsigned_or_stale_policies_are_refused(env):
    client, _, redis, _, _, auth = env
    unsigned = policy()
    del unsigned["signature"]
    assert (
        client.post("/v1/guardian/pairing-codes", json={"policy": unsigned}, headers=auth).status_code == 422
    )
    phone, child = pair(client, auth)
    assert (
        client.put(f"/v1/guardian/children/{child}/policy", json=policy(1), headers=auth).status_code == 409
    )
    r = client.put(
        f"/v1/guardian/children/{child}/policy", json=policy(2, watchedApps=["whatsapp"]), headers=auth
    )
    assert r.status_code == 200
    assert_publishes("guardian", redis.events("guardian.policy.updated")[0])
    # The phone gets the new policy at its next heartbeat.
    hb = phone.post(
        "/v1/guardian/heartbeat",
        {"deviceId": phone.device_id, "appVersion": "0.1.0", "policyVersion": 1, "protectionActive": True},
    )
    validate_body("api/heartbeat-response.json", hb.json())
    assert hb.json()["policy"]["policyVersion"] == 2


def test_alert_from_the_phone_reaches_the_parent(env):
    client, _, redis, push, _, auth = env
    phone, child = pair(client, auth)
    r = phone.post(
        "/v1/guardian/alerts",
        {
            "caseId": CASE,
            "severity": "high",
            "category": "threat",
            "kind": "flag",
            "appSource": "whatsapp",
            "occurredAt": "2026-11-05T09:30:00Z",
            "sealedExcerpt": EXCERPT,
        },
    )
    assert r.status_code == 201, r.text
    validate_body("types/alert.json", r.json())
    alerts = client.get("/v1/guardian/alerts", headers=auth).json()
    validate_body("api/alert-list.json", alerts)
    assert alerts["alerts"][0]["sealedExcerpt"] == EXCERPT and alerts["alerts"][0]["childId"] == child
    assert_publishes("guardian", redis.events("guardian.alert.sent")[0])
    # The push holds IDs and severity only.
    assert set(push.sent[-1][1]) == {"alertId", "kind", "severity"}


def test_alerts_need_a_paired_signed_device(env):
    client, *_ = env
    stranger = Phone(client)
    stranger.device_id = str(uuid.uuid4())
    assert stranger.post("/v1/guardian/alerts", {"caseId": CASE, "severity": "high"}).status_code == 401
    assert client.post("/v1/guardian/alerts", json={"caseId": CASE, "severity": "high"}).status_code in (
        401,
        422,
    )


def test_message_text_is_refused(env):
    client, _, _, _, _, auth = env
    phone, _ = pair(client, auth)
    r = phone.post("/v1/guardian/alerts", {"caseId": CASE, "severity": "high", "text": "the message"})
    assert r.status_code == 422


def test_share_approval_round_trip(env):
    client, _, redis, _, _, auth = env
    phone, child = pair(client, auth)
    rid = str(uuid.uuid4())
    r = phone.post(
        "/v1/guardian/approval-requests",
        {"requestId": rid, "caseId": CASE, "kind": "share", "recipientType": "school"},
    )
    assert r.status_code == 201
    validate_body("api/approval.json", r.json())
    approvals = client.get("/v1/guardian/approvals", headers=auth).json()
    validate_body("api/approval-list.json", approvals)
    approval_id = approvals["approvals"][0]["approvalId"]
    # Tells the parent's phone which child's family key signs the decision.
    assert approvals["approvals"][0]["childId"] == child
    assert (
        client.post(
            f"/v1/guardian/approvals/{approval_id}", json={"decision": "approve"}, headers=auth
        ).status_code
        == 422
    )
    d = client.post(
        f"/v1/guardian/approvals/{approval_id}", json={"decision": "approve", "signature": SIG}, headers=auth
    )
    assert d.status_code == 200
    validate_body("api/approval-result.json", d.json())
    assert_publishes("guardian", redis.events("guardian.share.approved")[0])
    assert (
        client.post(
            f"/v1/guardian/approvals/{approval_id}",
            json={"decision": "decline", "signature": SIG},
            headers=auth,
        ).status_code
        == 409
    )
    # Delivered to the phone once, at its next heartbeat.
    hb = {"deviceId": phone.device_id, "appVersion": "0.1.0", "policyVersion": 1}
    first = phone.post("/v1/guardian/heartbeat", hb).json()
    assert [x["requestId"] for x in first["decisions"]] == [rid]
    assert first["decisions"][0]["signature"] == SIG
    assert phone.post("/v1/guardian/heartbeat", hb).json()["decisions"] == []


def test_parents_cannot_touch_other_families(env):
    client, _, _, _, _, auth = env
    _, child = pair(client, auth)
    other = client.post(
        "/v1/guardian/parents", json={"devicePublicKey": public_der(ec.generate_private_key(ec.SECP256R1()))}
    ).json()
    other_auth = {"Authorization": f"Bearer {other['sessionToken']}"}
    assert (
        client.put(f"/v1/guardian/children/{child}/policy", json=policy(2), headers=other_auth).status_code
        == 404
    )
    assert client.get("/v1/guardian/alerts", headers=other_auth).json() == {"alerts": []}
    assert client.get("/v1/guardian/children", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_tamper_alerts(env):
    client, app, _, _, clock, auth = env
    phone, child = pair(client, auth)
    hb = {"deviceId": phone.device_id, "appVersion": "0.1.0", "policyVersion": 1}
    # Accessibility turned off on the child's phone.
    phone.post(
        "/v1/guardian/heartbeat",
        {
            **hb,
            "protectionActive": False,
            "missingPermissions": ["android.permission.BIND_ACCESSIBILITY_SERVICE"],
        },
    )
    status = client.get("/v1/guardian/children", headers=auth).json()["children"][0]
    assert status["protectionActive"] is False and status["missingPermissions"]
    phone.post("/v1/guardian/heartbeat", {**hb, "protectionActive": False})  # no duplicate alert
    alerts = client.get("/v1/guardian/alerts", headers=auth).json()["alerts"]
    assert [(a["kind"], a["caseId"]) for a in alerts] == [("tamper", child)]
    # Restored, then the phone goes silent (app removed): the sweep reports it.
    phone.post("/v1/guardian/heartbeat", {**hb, "protectionActive": True})
    clock.now += TAMPER_AFTER + dt.timedelta(minutes=1)
    assert app.state.sweep() == 1
    assert app.state.sweep() == 0
    assert len(client.get("/v1/guardian/alerts", headers=auth).json()["alerts"]) == 2


def test_parent_report_round_trip(env):
    client, _, redis, _, _, auth = env
    phone, child = pair(client, auth)
    rid = str(uuid.uuid4())
    req = {
        "reportId": rid,
        "caseId": CASE,
        "recipientType": "police",
        "anonymous": True,
        "signature": SIG,
        "sealedChildName": EXCERPT,
    }
    r = client.post(f"/v1/guardian/children/{child}/reports", json=req, headers=auth)
    assert r.status_code == 201, r.text
    validate_body("api/report.json", r.json())
    assert r.json()["status"] == "requested"
    assert client.post(f"/v1/guardian/children/{child}/reports", json=req, headers=auth).status_code == 409
    # The parent is the approver: Casefile may accept the share.
    approved = redis.events("guardian.share.approved")[-1]
    assert approved["payload"]["requestId"] == rid
    assert_publishes("guardian", approved)
    # Delivered to the phone once, at its heartbeat, with the parent's signature.
    hb = {"deviceId": phone.device_id, "appVersion": "0.1.0", "policyVersion": 1}
    first = phone.post("/v1/guardian/heartbeat", hb).json()
    validate_body("api/heartbeat-response.json", first)
    assert [(o["reportId"], o["signature"], o["sealedChildName"]) for o in first["reports"]] == [
        (rid, SIG, EXCERPT)
    ]
    assert phone.post("/v1/guardian/heartbeat", hb).json()["reports"] == []
    # The phone returns the link, sealed for the parent.
    done = phone.post(f"/v1/guardian/reports/{rid}/result", {"status": "ready", "sealedLink": EXCERPT})
    assert done.status_code == 200, done.text
    listed = client.get("/v1/guardian/reports", headers=auth).json()
    validate_body("api/report-list.json", listed)
    assert listed["reports"][0]["status"] == "ready" and listed["reports"][0]["sealedLink"] == EXCERPT
    assert phone.post(f"/v1/guardian/reports/{rid}/result", {"status": "ready"}).status_code == 422


def test_reports_are_per_family(env):
    client, *_, auth = env
    _, child = pair(client, auth)
    other = client.post(
        "/v1/guardian/parents", json={"devicePublicKey": public_der(ec.generate_private_key(ec.SECP256R1()))}
    ).json()
    other_auth = {"Authorization": f"Bearer {other['sessionToken']}"}
    req = {
        "reportId": str(uuid.uuid4()),
        "caseId": CASE,
        "recipientType": "school",
        "anonymous": True,
        "signature": SIG,
    }
    assert (
        client.post(f"/v1/guardian/children/{child}/reports", json=req, headers=other_auth).status_code == 404
    )
    assert client.get("/v1/guardian/reports", headers=other_auth).json() == {"reports": []}
