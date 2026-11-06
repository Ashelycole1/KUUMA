"""The Casefile service against its contract: timestamps, bundles, shares and verification."""

import datetime as dt
import hashlib
import uuid

import pytest
from fastapi.testclient import TestClient
from kuuma_casefile_service.app import build_app, sqlite_engine
from kuuma_casefile_service.infra import MemoryStore, MockTimestampAuthority

from kuuma_contracts.testing import assert_publishes, validate_body
from kuuma_server_common.testing import MemoryStreams

CASE = "c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a"
CHILD = "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b"
ROOT = hashlib.sha256(b"chain root").hexdigest()


class Clock:
    def __init__(self):
        self.now = dt.datetime(2026, 11, 5, 9, 30, tzinfo=dt.UTC)

    def __call__(self):
        return self.now


@pytest.fixture
def env():
    store, redis, clock = MemoryStore(), MemoryStreams(), Clock()
    app = build_app(engine=sqlite_engine(), store=store, redis=redis, clock=clock, consume=False)
    return TestClient(app), app, store, redis, clock


def upload_bundle(client, store, case=CASE):
    blob = b"KCF1" + bytes(64)  # ciphertext: the service never sees anything else
    r = client.post(
        f"/v1/cases/{case}/bundles",
        json={"bundleHash": hashlib.sha256(blob).hexdigest(), "itemCount": 3, "sizeBytes": len(blob)},
    )
    assert r.status_code == 201, r.text
    validate_body("api/bundle-response.json", r.json())
    key = r.json()["uploadUrl"].split("kuuma-bundles/")[1].split("?")[0]
    store.put(key, blob)
    return r.json()["bundleId"]


def test_timestamp_is_stable_and_verifies(env):
    client, *_ = env
    r = client.post("/v1/cases/timestamps", json={"hash": ROOT})
    assert r.status_code == 201
    validate_body("api/timestamp-response.json", r.json())
    assert client.post("/v1/cases/timestamps", json={"hash": ROOT}).json() == r.json()
    assert MockTimestampAuthority().verify(ROOT, r.json()["timestamp"], r.json()["signature"])
    v = client.get(f"/v1/verify/{ROOT}").json()
    validate_body("api/verify-response.json", v)
    assert v == {"hash": ROOT, "found": True, "timestamp": r.json()["timestamp"], "chainValid": True}
    assert client.get(f"/v1/verify/{'0' * 64}").json() == {"hash": "0" * 64, "found": False}
    assert client.get("/v1/verify/not-a-hash").status_code == 422


def test_adult_share_completes_and_downloads(env):
    client, app, store, redis, clock = env
    bundle = upload_bundle(client, store)
    rid = str(uuid.uuid4())
    r = client.post(
        f"/v1/cases/{CASE}/shares", json={"requestId": rid, "recipientType": "school", "bundleId": bundle}
    )
    assert r.status_code == 202
    validate_body("api/share-response.json", r.json())
    assert r.json()["status"] == "completed"
    completed = redis.events("casefile.share.completed")
    assert len(completed) == 1
    assert_publishes("casefile", completed[0])
    d = client.get(f"/v1/verify/shares/{rid}")
    assert d.status_code == 200
    validate_body("api/share-download.json", d.json())
    # Idempotent retry.
    assert (
        client.post(f"/v1/cases/{CASE}/shares", json={"requestId": rid, "recipientType": "school"}).json()[
            "status"
        ]
        == "completed"
    )
    # Expiry.
    clock.now += dt.timedelta(days=31)
    assert client.get(f"/v1/verify/shares/{rid}").status_code == 410


def test_child_share_waits_for_parent(env):
    client, app, store, redis, _ = env
    app.state.handle_event(
        {
            "type": "guardian.pairing.completed",
            "payload": {"childId": str(uuid.uuid4()), "deviceId": CHILD, "ageBracket": "age_13_15"},
        }
    )
    upload_bundle(client, store)
    rid = str(uuid.uuid4())
    r = client.post(
        f"/v1/cases/{CASE}/shares",
        json={"requestId": rid, "recipientType": "police"},
        headers={"X-Kuuma-Device-Id": CHILD},
    )
    assert r.json()["status"] == "pending_approval"
    assert client.get(f"/v1/verify/shares/{rid}").status_code == 403
    assert not redis.events("casefile.share.completed")
    app.state.handle_event(
        {
            "type": "guardian.share.approved",
            "payload": {"requestId": rid, "caseId": CASE, "decidedAt": "2026-11-05T09:40:00Z"},
        }
    )
    assert client.get(f"/v1/verify/shares/{rid}").status_code == 200
    assert len(redis.events("casefile.share.completed")) == 1


def test_declined_share_never_opens(env):
    client, app, store, *_ = env
    app.state.handle_event(
        {
            "type": "guardian.pairing.completed",
            "payload": {"childId": str(uuid.uuid4()), "deviceId": CHILD, "ageBracket": "age_13_15"},
        }
    )
    upload_bundle(client, store)
    rid = str(uuid.uuid4())
    # The decision may arrive before the share itself.
    app.state.handle_event(
        {
            "type": "guardian.share.declined",
            "payload": {"requestId": rid, "caseId": CASE, "decidedAt": "2026-11-05T09:40:00Z"},
        }
    )
    r = client.post(
        f"/v1/cases/{CASE}/shares",
        json={"requestId": rid, "recipientType": "platform"},
        headers={"X-Kuuma-Device-Id": CHILD},
    )
    assert r.json()["status"] == "declined"
    assert client.get(f"/v1/verify/shares/{rid}").status_code == 410


def test_share_needs_an_uploaded_bundle(env):
    client, _, store, *_ = env
    r = client.post(
        f"/v1/cases/{CASE}/shares", json={"requestId": str(uuid.uuid4()), "recipientType": "school"}
    )
    assert r.status_code == 404
    validate_body("types/error-response.json", r.json())
    client.post(f"/v1/cases/{CASE}/bundles", json={"bundleHash": ROOT, "itemCount": 1, "sizeBytes": 10})
    r = client.post(
        f"/v1/cases/{CASE}/shares", json={"requestId": str(uuid.uuid4()), "recipientType": "school"}
    )
    assert r.status_code == 409 and r.json()["error"]["code"] == "bundle_not_uploaded"


def test_text_is_refused_by_the_contract(env):
    client, *_ = env
    r = client.post(
        f"/v1/cases/{CASE}/bundles", json={"bundleHash": ROOT, "itemCount": 1, "sizeBytes": 10, "text": "hi"}
    )
    assert r.status_code == 422
    assert client.get("/v1/verify/shares/nope").status_code == 404
