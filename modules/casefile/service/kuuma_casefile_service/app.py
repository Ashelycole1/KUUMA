"""Casefile service: trusted timestamps, encrypted bundle slots, expiring shares, verification.

The cloud never sees message text or contact details. Phones send hash roots, bundle
hashes and sizes; bundles are encrypted on the phone with a key the server never gets.
"""

from __future__ import annotations

import datetime as dt
import os
import re
import threading
import uuid
from collections.abc import Callable

from fastapi import Depends, FastAPI, Header, Request
from fastapi.responses import JSONResponse
from sqlalchemy import create_engine, event, select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from kuuma_casefile_service.db import Base, Bundle, ChildDevice, Share, ShareDecision, Timestamp
from kuuma_casefile_service.infra import MemoryStore, MinioStore, MockTimestampAuthority, ObjectStore
from kuuma_contracts.testing import ContractViolation, validate_body
from kuuma_server_common import KuumaError, StreamConsumer, StreamPublisher, create_app, get_logger
from kuuma_server_common.seed import seed
from kuuma_server_common.service import envelope, redis_client

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
UPLOAD_WINDOW = dt.timedelta(hours=1)
SHARE_LIFETIME = dt.timedelta(days=30)
DOWNLOAD_WINDOW = dt.timedelta(minutes=15)
log = get_logger("casefile")


def iso(t: dt.datetime) -> str:
    return t.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def aware(t: dt.datetime) -> dt.datetime:
    return t if t.tzinfo else t.replace(tzinfo=dt.UTC)


def sqlite_engine() -> Engine:
    """In-memory SQLite with the `casefile` schema attached (tests and `KUUMA_DB=memory`)."""
    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})

    @event.listens_for(engine, "connect")
    def _attach(conn, _):  # noqa: ANN001
        conn.execute("ATTACH DATABASE ':memory:' AS casefile")

    Base.metadata.create_all(engine)
    return engine


def body(schema: str):
    """Dependency: parse and contract-validate a JSON body."""

    async def dependency(request: Request) -> dict:
        try:
            data = await request.json()
            validate_body(schema, data)
        except (ValueError, ContractViolation):
            raise KuumaError(422, "invalid_body", f"Body does not match {schema}") from None
        return data

    return dependency


def build_app(
    engine: Engine | None = None,
    store: ObjectStore | None = None,
    redis=None,
    tsa: MockTimestampAuthority | None = None,
    clock: Callable[[], dt.datetime] = lambda: dt.datetime.now(dt.UTC),
    consume: bool = True,
) -> FastAPI:
    if engine is None:
        if os.environ.get("DATABASE_URL_CASEFILE"):
            from kuuma_server_common.db import make_engine

            engine = make_engine("casefile")
        else:
            engine = sqlite_engine()
    store = store or (MinioStore() if os.environ.get("MINIO_ENDPOINT") else MemoryStore())
    redis = redis if redis is not None else redis_client()
    tsa = tsa or MockTimestampAuthority()
    sessions = sessionmaker(engine, expire_on_commit=False)

    app = create_app("casefile", api_prefixes=["/v1/cases", "/v1/verify"])
    app.state.bus = StreamPublisher(redis, "casefile")
    app.state.store = store
    app.state.sessions = sessions

    def db():
        with sessions() as s:
            yield s

    def case_path(case_id: str) -> str:
        if not UUID.match(case_id):
            raise KuumaError(422, "invalid_case_id", "caseId must be a UUID")
        return case_id

    # ------------------------------------------------------------------ timestamps

    @app.post("/v1/cases/timestamps", status_code=201)
    def create_timestamp(data: dict = Depends(body("api/timestamp-request.json")), s: Session = Depends(db)):
        h = data["hash"]
        row = s.get(Timestamp, h)
        if row is None:
            ts, sig = tsa.stamp(h, clock())
            row = Timestamp(hash=h, timestamp=ts, signature=sig, authority=tsa.name)
            s.add(row)
            s.commit()
            log.info("timestamp.issued", hash=h)
        return {
            "hash": row.hash,
            "timestamp": row.timestamp,
            "signature": row.signature,
            "authority": row.authority,
        }

    # ------------------------------------------------------------------ bundles

    @app.post("/v1/cases/{caseId}/bundles", status_code=201)
    def create_bundle(
        caseId: str,  # noqa: N803 - contract name
        data: dict = Depends(body("api/bundle-request.json")),
        s: Session = Depends(db),
    ):
        case_id = case_path(caseId)
        now = clock()
        bundle_id = str(uuid.uuid4())
        key = f"bundles/{case_id}/{bundle_id}"
        s.add(
            Bundle(
                bundle_id=bundle_id,
                case_id=case_id,
                bundle_hash=data["bundleHash"],
                item_count=data["itemCount"],
                size_bytes=data["sizeBytes"],
                object_key=key,
                created_at=now,
                expires_at=now + SHARE_LIFETIME,
            )
        )
        s.commit()
        log.info("bundle.slot_created", bundle_id=bundle_id, size_bytes=data["sizeBytes"])
        return {
            "bundleId": bundle_id,
            "uploadUrl": store.upload_url(key, UPLOAD_WINDOW),
            "expiresAt": iso(now + UPLOAD_WINDOW),
        }

    # ------------------------------------------------------------------ shares

    def complete(s: Session, share: Share) -> None:
        share.status = "completed"
        s.commit()
        app.state.bus.publish(
            envelope(
                "casefile.share.completed",
                {
                    "requestId": share.request_id,
                    "caseId": share.case_id,
                    "recipientType": share.recipient_type,
                    "expiresAt": iso(aware(share.expires_at)),
                },
                device_id=share.device_id or seed()["child_device"]["device_id"],
                case_id=share.case_id,
            )
        )
        log.info("share.completed", request_id=share.request_id, recipient_type=share.recipient_type)

    def share_body(share: Share) -> dict:
        out = {"requestId": share.request_id, "caseId": share.case_id, "status": share.status}
        if share.status == "completed":
            out["expiresAt"] = iso(aware(share.expires_at))
        return out

    @app.post("/v1/cases/{caseId}/shares", status_code=202)
    def create_share(
        caseId: str,  # noqa: N803
        data: dict = Depends(body("api/share-request.json")),
        x_kuuma_device_id: str | None = Header(default=None),
        s: Session = Depends(db),
    ):
        case_id = case_path(caseId)
        existing = s.get(Share, data["requestId"])
        if existing is not None:
            if existing.case_id != case_id:
                raise KuumaError(409, "conflict", "requestId belongs to another case")
            return JSONResponse(share_body(existing), status_code=202)
        if data.get("bundleId"):
            bundle = s.get(Bundle, data["bundleId"])
        else:
            bundle = s.scalars(
                select(Bundle).where(Bundle.case_id == case_id).order_by(Bundle.created_at.desc())
            ).first()
        if bundle is None or bundle.case_id != case_id:
            raise KuumaError(404, "bundle_not_found", "No bundle for this case")
        if not store.exists(bundle.object_key):
            raise KuumaError(409, "bundle_not_uploaded", "Upload the encrypted bundle first")
        device = x_kuuma_device_id if x_kuuma_device_id and UUID.match(x_kuuma_device_id) else None
        share = Share(
            request_id=data["requestId"],
            case_id=case_id,
            recipient_type=data["recipientType"],
            bundle_id=bundle.bundle_id,
            status="pending_approval",
            device_id=device,
            created_at=clock(),
            expires_at=min(aware(bundle.expires_at), clock() + SHARE_LIFETIME),
        )
        s.add(share)
        s.commit()
        is_child = device is not None and s.get(ChildDevice, device) is not None
        decision = s.get(ShareDecision, share.request_id)
        if not is_child or (decision and decision.approved):
            complete(s, share)
        elif decision is not None:
            share.status = "declined"
            s.commit()
        return JSONResponse(share_body(share), status_code=202)

    # ------------------------------------------------------------------ verification (public)

    @app.get("/v1/verify/shares/{requestId}")
    def get_share(requestId: str, s: Session = Depends(db)):  # noqa: N803
        if not UUID.match(requestId):
            raise KuumaError(404, "not_found", "No such share")
        share = s.get(Share, requestId)
        if share is None:
            raise KuumaError(404, "not_found", "No such share")
        if share.status == "pending_approval":
            raise KuumaError(403, "share_pending", "This share is waiting for approval")
        if share.status != "completed" or aware(share.expires_at) < clock():
            raise KuumaError(410, "share_unavailable", "This share was declined or has expired")
        bundle = s.get(Bundle, share.bundle_id)
        window = min(DOWNLOAD_WINDOW, aware(share.expires_at) - clock())
        log.info("share.downloaded", request_id=requestId)
        return {
            "requestId": requestId,
            "bundleHash": bundle.bundle_hash,
            "downloadUrl": store.download_url(bundle.object_key, window),
            "expiresAt": iso(aware(share.expires_at)),
        }

    @app.get("/v1/verify/{hash}")
    def verify_hash(hash: str, s: Session = Depends(db)):  # noqa: A002 - contract name
        if not SHA256.match(hash):
            raise KuumaError(422, "invalid_hash", "hash must be 64 lowercase hex characters")
        row = s.get(Timestamp, hash)
        if row is None:
            return {"hash": hash, "found": False}
        valid = tsa.verify(row.hash, row.timestamp, row.signature)
        return {"hash": hash, "found": True, "timestamp": row.timestamp, "chainValid": valid}

    # ------------------------------------------------------------------ events from Guardian

    def handle(ev: dict) -> None:
        p = ev["payload"]
        with sessions() as s:
            if ev["type"] == "guardian.pairing.completed":
                if s.get(ChildDevice, p["deviceId"]) is None:
                    s.add(ChildDevice(device_id=p["deviceId"]))
                    s.commit()
                return
            approved = ev["type"] == "guardian.share.approved"
            if s.get(ShareDecision, p["requestId"]) is None:
                s.add(ShareDecision(request_id=p["requestId"], approved=approved, decided_at=p["decidedAt"]))
                s.commit()
            share = s.get(Share, p["requestId"])
            if share is not None and share.status == "pending_approval":
                if approved:
                    complete(s, share)
                else:
                    share.status = "declined"
                    s.commit()

    app.state.handle_event = handle
    topics = ["guardian.pairing.completed", "guardian.share.approved", "guardian.share.declined"]
    if consume and os.environ.get("REDIS_URL"):
        consumer = StreamConsumer(redis, "casefile", topics)

        def loop() -> None:
            while True:
                try:
                    for ev in consumer.poll(block_ms=5000):
                        handle(ev)
                except Exception:  # noqa: BLE001 - keep consuming; never log payloads
                    log.error("stream.consume_failed")

        threading.Thread(target=loop, name="casefile-events", daemon=True).start()
    return app


app = build_app()
