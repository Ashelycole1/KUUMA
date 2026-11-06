"""Guardian service: parent accounts, pairing, signed policies, alerts, approvals, heartbeats.

Trust model
-----------
* Child phones authenticate with device signatures (key registered at pairing).
* Parents authenticate with a session token bound to their phone's key.
* Policies and approval decisions carry a signature made on the parent's phone with the
  family key, which travels only in the pairing QR code. This service stores and relays
  them but can neither read alert excerpts nor forge a policy or a decision.
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import threading
import time
import uuid
from collections.abc import Callable
from typing import Protocol

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, FastAPI, Header, Request
from fastapi.responses import JSONResponse
from sqlalchemy import create_engine, event, select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from kuuma_contracts.testing import ContractViolation, validate_body
from kuuma_guardian_service.db import (
    Alert,
    Approval,
    Base,
    Child,
    EvidenceRequest,
    PairingCode,
    Parent,
    Report,
)
from kuuma_server_common import KuumaError, StreamPublisher, audit, create_app, device_auth, get_logger
from kuuma_server_common.service import envelope, redis_client

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
PAIRING_CODE_LIFETIME = dt.timedelta(minutes=15)
# PLACEHOLDER until the team agrees it: a phone silent this long is reported as tampered with.
TAMPER_AFTER = dt.timedelta(minutes=60)
log = get_logger("guardian")


def iso(t: dt.datetime) -> str:
    return t.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def aware(t: dt.datetime | None) -> dt.datetime | None:
    return None if t is None else (t if t.tzinfo else t.replace(tzinfo=dt.UTC))


def sqlite_engine() -> Engine:
    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})

    @event.listens_for(engine, "connect")
    def _attach(conn, _):  # noqa: ANN001
        conn.execute("ATTACH DATABASE ':memory:' AS guardian")

    Base.metadata.create_all(engine)
    return engine


class PushSender(Protocol):
    """Sends a neutral push to a parent's phone. Payloads hold IDs and severity only."""

    def send(self, parent_id: str, data: dict[str, str]) -> None: ...


class RecordingPush:
    """Default until a Firebase project exists (see the Guardian brief): records, sends nothing."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, dict[str, str]]] = []

    def send(self, parent_id: str, data: dict[str, str]) -> None:
        self.sent.append((parent_id, data))


def body(schema: str):
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
    redis=None,
    push: PushSender | None = None,
    clock: Callable[[], dt.datetime] = lambda: dt.datetime.now(dt.UTC),
    sweep_every: float | None = None,
) -> FastAPI:
    if engine is None:
        if os.environ.get("DATABASE_URL_GUARDIAN"):
            from kuuma_server_common.db import make_engine

            engine = make_engine("guardian")
        else:
            engine = sqlite_engine()
    redis = redis if redis is not None else redis_client()
    push = push or RecordingPush()
    sessions = sessionmaker(engine, expire_on_commit=False)

    app = create_app("guardian", api_prefixes=["/v1/guardian"])
    app.state.bus = StreamPublisher(redis, "guardian")
    app.state.push = push
    app.state.sessions = sessions

    def db():
        with sessions() as s:
            yield s

    class DbDeviceKeys:
        def public_key(self, device_id: str):
            with sessions() as s:
                child = s.scalars(select(Child).where(Child.device_id == device_id)).first()
                if child is None or not child.device_public_key:
                    return None
                return serialization.load_der_public_key(base64.b64decode(child.device_public_key))

    device = device_auth(DbDeviceKeys())

    def device_child(identity=Depends(device), s: Session = Depends(db)) -> Child:
        child = s.scalars(select(Child).where(Child.device_id == identity.device_id)).first()
        if child is None:
            raise KuumaError(401, "unauthorized", "Unknown device")
        return child

    def parent(authorization: str = Header(default=""), s: Session = Depends(db)) -> Parent:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise KuumaError(401, "unauthorized", "Parent session required")
        row = s.scalars(
            select(Parent).where(Parent.token_hash == hashlib.sha256(token.encode()).hexdigest())
        ).first()
        if row is None:
            raise KuumaError(401, "unauthorized", "Unknown session")
        return row

    def owned_child(s: Session, p: Parent, child_id: str) -> Child:
        child = s.get(Child, child_id) if UUID.match(child_id) else None
        if child is None or child.parent_id != p.parent_id:
            raise KuumaError(404, "not_found", "No such child")
        return child

    def publish(event_type: str, payload: dict, device_id: str | None, case_id: str | None = None) -> None:
        app.state.bus.publish(
            envelope(event_type, payload, device_id=device_id or str(uuid.uuid4()), case_id=case_id)
        )

    def alert_body(a: Alert) -> dict:
        out = {
            "alertId": a.alert_id,
            "caseId": a.case_id,
            "childId": a.child_id,
            "kind": a.kind,
            "severity": a.severity,
            "createdAt": iso(aware(a.created_at)),
            "read": a.read,
        }
        for k, v in (
            ("category", a.category),
            ("appSource", a.app_source),
            ("occurredAt", a.occurred_at),
            ("sealedExcerpt", a.sealed_excerpt),
        ):
            if v:
                out[k] = v
        return out

    def raise_alert(
        s: Session,
        child: Child,
        *,
        kind: str,
        severity: str,
        case_id: str,
        category=None,
        app_source=None,
        occurred_at=None,
        sealed_excerpt=None,
    ) -> Alert:
        a = Alert(
            alert_id=str(uuid.uuid4()),
            child_id=child.child_id,
            case_id=case_id,
            kind=kind,
            severity=severity,
            category=category,
            app_source=app_source,
            occurred_at=occurred_at,
            sealed_excerpt=sealed_excerpt,
            created_at=clock(),
            read=False,
        )
        s.add(a)
        s.commit()
        publish(
            "guardian.alert.sent",
            {"alertId": a.alert_id, "caseId": case_id, "severity": severity},
            child.device_id,
            case_id,
        )
        # Neutral push: nothing that reveals the content on a lock screen.
        push.send(child.parent_id, {"alertId": a.alert_id, "kind": kind, "severity": severity})
        log.info("alert.raised", alert_id=a.alert_id, kind=kind, severity=severity)
        return a

    def approval_result(a: Approval) -> dict:
        out = {
            "approvalId": a.approval_id,
            "requestId": a.request_id,
            "caseId": a.case_id,
            "kind": a.kind,
            "decision": "approve" if a.status == "approved" else "decline",
            "decidedAt": iso(aware(a.decided_at)),
        }
        if a.signature:
            out["signature"] = a.signature
        return out

    def approval_body(a: Approval) -> dict:
        out = {
            "approvalId": a.approval_id,
            "requestId": a.request_id,
            "caseId": a.case_id,
            "childId": a.child_id,
            "kind": a.kind,
            "status": a.status,
            "createdAt": iso(aware(a.created_at)),
        }
        if a.recipient_type:
            out["recipientType"] = a.recipient_type
        if a.decided_at:
            out["decidedAt"] = iso(aware(a.decided_at))
        return out

    def child_status(c: Child) -> dict:
        out = {
            "childId": c.child_id,
            "paired": c.paired,
            "ageBracket": c.age_bracket,
            "policyVersion": c.policy_version,
            "missingPermissions": json.loads(c.missing_permissions or "[]"),
        }
        if c.last_heartbeat_at:
            out["lastHeartbeatAt"] = iso(aware(c.last_heartbeat_at))
        if c.protection_active is not None:
            out["protectionActive"] = c.protection_active
        return out

    # ------------------------------------------------------------------ parents

    @app.post("/v1/guardian/parents", status_code=201)
    def register_parent(
        data: dict = Depends(body("api/parent-registration-request.json")), s: Session = Depends(db)
    ):
        try:
            key = serialization.load_der_public_key(base64.b64decode(data["devicePublicKey"]))
            if not isinstance(key, ec.EllipticCurvePublicKey):
                raise ValueError
        except ValueError:
            raise KuumaError(422, "bad_device_key", "devicePublicKey must be an EC public key") from None
        token = secrets.token_urlsafe(32)
        p = Parent(
            parent_id=str(uuid.uuid4()),
            token_hash=hashlib.sha256(token.encode()).hexdigest(),
            device_public_key=data["devicePublicKey"],
            created_at=clock(),
        )
        s.add(p)
        s.commit()
        audit("parent.registered", actor=p.parent_id, service="guardian")
        return {"parentId": p.parent_id, "sessionToken": token}

    @app.post("/v1/guardian/pairing-codes", status_code=201)
    def create_pairing_code(
        data: dict = Depends(body("api/pairing-code-request.json")),
        p: Parent = Depends(parent),
        s: Session = Depends(db),
    ):
        policy = data["policy"]
        if "signature" not in policy:
            raise KuumaError(422, "unsigned_policy", "The policy must be signed by the parent's phone")
        now = clock()
        child = Child(
            child_id=str(uuid.uuid4()),
            parent_id=p.parent_id,
            age_bracket=policy["ageBracket"],
            policy_json=json.dumps(policy),
            policy_version=policy["policyVersion"],
            paired=False,
            created_at=now,
            missing_permissions="[]",
            tamper_open=False,
        )
        s.add(child)
        while True:
            code = f"{secrets.randbelow(10**6):06d}"
            existing = s.get(PairingCode, code)
            if existing is None or existing.used or aware(existing.expires_at) < now:
                if existing is not None:
                    s.delete(existing)
                    s.flush()
                break
        s.add(
            PairingCode(
                code=code, child_id=child.child_id, expires_at=now + PAIRING_CODE_LIFETIME, used=False
            )
        )
        s.commit()
        return {"childId": child.child_id, "pairingCode": code, "expiresAt": iso(now + PAIRING_CODE_LIFETIME)}

    @app.get("/v1/guardian/children")
    def list_children(p: Parent = Depends(parent), s: Session = Depends(db)):
        rows = s.scalars(select(Child).where(Child.parent_id == p.parent_id).order_by(Child.created_at)).all()
        return {"children": [child_status(c) for c in rows]}

    @app.put("/v1/guardian/children/{childId}/policy")
    def put_policy(
        childId: str,  # noqa: N803
        policy: dict = Depends(body("types/policy.json")),
        p: Parent = Depends(parent),
        s: Session = Depends(db),
    ):
        child = owned_child(s, p, childId)
        if "signature" not in policy:
            raise KuumaError(422, "unsigned_policy", "The policy must be signed by the parent's phone")
        if policy["policyVersion"] <= child.policy_version:
            raise KuumaError(409, "stale_policy", "policyVersion must be higher than the current one")
        child.policy_json, child.policy_version, child.age_bracket = (
            json.dumps(policy),
            policy["policyVersion"],
            policy["ageBracket"],
        )
        s.commit()
        publish(
            "guardian.policy.updated",
            {
                "childId": child.child_id,
                "ageBracket": policy["ageBracket"],
                "watchedApps": policy["watchedApps"],
                "thresholds": policy["thresholds"],
                "policyVersion": policy["policyVersion"],
            },
            child.device_id,
        )
        audit("policy.updated", actor=p.parent_id, resource=child.child_id, service="guardian")
        return policy

    @app.get("/v1/guardian/alerts")
    def list_alerts(p: Parent = Depends(parent), s: Session = Depends(db)):
        child_ids = [c.child_id for c in s.scalars(select(Child).where(Child.parent_id == p.parent_id)).all()]
        rows = s.scalars(
            select(Alert).where(Alert.child_id.in_(child_ids)).order_by(Alert.created_at.desc()).limit(200)
        ).all()
        return {"alerts": [alert_body(a) for a in rows]}

    @app.get("/v1/guardian/approvals")
    def list_approvals(p: Parent = Depends(parent), s: Session = Depends(db)):
        child_ids = [c.child_id for c in s.scalars(select(Child).where(Child.parent_id == p.parent_id)).all()]
        rows = s.scalars(
            select(Approval).where(Approval.child_id.in_(child_ids)).order_by(Approval.created_at.desc())
        ).all()
        return {"approvals": [approval_body(a) for a in rows]}

    @app.post("/v1/guardian/approvals/{approvalId}")
    def decide(
        approvalId: str,  # noqa: N803
        data: dict = Depends(body("api/approval-decision-request.json")),
        p: Parent = Depends(parent),
        s: Session = Depends(db),
    ):
        a = s.get(Approval, approvalId) if UUID.match(approvalId) else None
        child = s.get(Child, a.child_id) if a else None
        if a is None or child is None or child.parent_id != p.parent_id:
            raise KuumaError(404, "not_found", "No such approval")
        if a.status != "pending":
            raise KuumaError(409, "already_decided", "This request was already decided")
        if "signature" not in data:
            raise KuumaError(422, "unsigned_decision", "The decision must be signed by the parent's phone")
        a.status = "approved" if data["decision"] == "approve" else "declined"
        a.decided_at, a.signature, a.delivered = clock(), data["signature"], False
        s.commit()
        if a.kind == "share":
            publish(
                f"guardian.share.{'approved' if a.status == 'approved' else 'declined'}",
                {"requestId": a.request_id, "caseId": a.case_id, "decidedAt": iso(aware(a.decided_at))},
                child.device_id,
                a.case_id,
            )
        audit(f"approval.{a.status}", actor=p.parent_id, resource=a.approval_id, service="guardian")
        return approval_result(a)

    # ------------------------------------------------------------------ parent reports

    def report_body(r: Report) -> dict:
        out = {
            "reportId": r.report_id,
            "childId": r.child_id,
            "caseId": r.case_id,
            "recipientType": r.recipient_type,
            "anonymous": r.anonymous,
            "status": r.status,
            "createdAt": iso(aware(r.created_at)),
        }
        if r.completed_at:
            out["completedAt"] = iso(aware(r.completed_at))
        if r.sealed_link:
            out["sealedLink"] = r.sealed_link
        return out

    def report_order(r: Report) -> dict:
        out = {
            "reportId": r.report_id,
            "caseId": r.case_id,
            "recipientType": r.recipient_type,
            "anonymous": r.anonymous,
            "signature": r.signature,
            "requestedAt": iso(aware(r.created_at)),
        }
        if r.sealed_child_name:
            out["sealedChildName"] = r.sealed_child_name
        return out

    @app.post("/v1/guardian/children/{childId}/reports", status_code=201)
    def create_report(
        childId: str,  # noqa: N803
        data: dict = Depends(body("api/report-request.json")),
        p: Parent = Depends(parent),
        s: Session = Depends(db),
    ):
        child = owned_child(s, p, childId)
        if not child.paired:
            raise KuumaError(409, "not_paired", "This child's phone is not paired yet")
        if s.get(Report, data["reportId"]) is not None:
            raise KuumaError(409, "duplicate_report", "This report was already requested")
        r = Report(
            report_id=data["reportId"],
            child_id=child.child_id,
            case_id=data["caseId"],
            recipient_type=data["recipientType"],
            anonymous=data["anonymous"],
            signature=data["signature"],
            sealed_child_name=data.get("sealedChildName"),
            status="requested",
            created_at=clock(),
            delivered=False,
        )
        s.add(r)
        s.commit()
        # The parent is the approver: Casefile's service may accept this share without waiting.
        publish(
            "guardian.share.approved",
            {"requestId": r.report_id, "caseId": r.case_id, "decidedAt": iso(aware(r.created_at))},
            child.device_id,
            r.case_id,
        )
        audit("report.requested", actor=p.parent_id, resource=r.report_id, service="guardian")
        return report_body(r)

    @app.get("/v1/guardian/reports")
    def list_reports(p: Parent = Depends(parent), s: Session = Depends(db)):
        ids = [c.child_id for c in s.scalars(select(Child).where(Child.parent_id == p.parent_id))]
        rows = s.scalars(
            select(Report).where(Report.child_id.in_(ids)).order_by(Report.created_at.desc())
        ).all()
        return {"reports": [report_body(r) for r in rows]}

    @app.post("/v1/guardian/reports/{reportId}/result")
    def report_result(
        reportId: str,  # noqa: N803
        data: dict = Depends(body("api/report-result-request.json")),
        child: Child = Depends(device_child),
        s: Session = Depends(db),
    ):
        r = s.get(Report, reportId) if UUID.match(reportId) else None
        if r is None or r.child_id != child.child_id:
            raise KuumaError(404, "not_found", "No such report")
        if data["status"] == "ready" and "sealedLink" not in data:
            raise KuumaError(422, "missing_link", "A ready report needs its sealed link")
        r.status, r.completed_at, r.sealed_link = data["status"], clock(), data.get("sealedLink")
        s.commit()
        audit(f"report.{r.status}", actor=child.child_id, resource=r.report_id, service="guardian")
        return report_body(r)

    # ------------------------------------------------------------------ evidence for the parent

    def evidence_body(e: EvidenceRequest) -> dict:
        out = {
            "requestId": e.request_id,
            "childId": e.child_id,
            "caseId": e.case_id,
            "status": e.status,
            "createdAt": iso(aware(e.created_at)),
        }
        if e.completed_at:
            out["completedAt"] = iso(aware(e.completed_at))
        if e.sealed_evidence:
            out["sealedEvidence"] = e.sealed_evidence
        return out

    def evidence_order(e: EvidenceRequest) -> dict:
        return {
            "requestId": e.request_id,
            "caseId": e.case_id,
            "signature": e.signature,
            "requestedAt": iso(aware(e.created_at)),
        }

    @app.post("/v1/guardian/children/{childId}/evidence", status_code=201)
    def create_evidence_request(
        childId: str,  # noqa: N803
        data: dict = Depends(body("api/evidence-request.json")),
        p: Parent = Depends(parent),
        s: Session = Depends(db),
    ):
        child = owned_child(s, p, childId)
        if not child.paired:
            raise KuumaError(409, "not_paired", "This child's phone is not paired yet")
        if s.get(EvidenceRequest, data["requestId"]) is not None:
            raise KuumaError(409, "duplicate_request", "This request was already made")
        e = EvidenceRequest(
            request_id=data["requestId"],
            child_id=child.child_id,
            case_id=data["caseId"],
            signature=data["signature"],
            status="requested",
            created_at=clock(),
            delivered=False,
        )
        s.add(e)
        s.commit()
        audit("evidence.requested", actor=p.parent_id, resource=e.request_id, service="guardian")
        return evidence_body(e)

    @app.get("/v1/guardian/evidence")
    def list_evidence(p: Parent = Depends(parent), s: Session = Depends(db)):
        ids = [c.child_id for c in s.scalars(select(Child).where(Child.parent_id == p.parent_id))]
        rows = s.scalars(
            select(EvidenceRequest)
            .where(EvidenceRequest.child_id.in_(ids))
            .order_by(EvidenceRequest.created_at.desc())
        ).all()
        return {"evidence": [evidence_body(e) for e in rows]}

    @app.post("/v1/guardian/evidence/{requestId}/result")
    def evidence_result(
        requestId: str,  # noqa: N803
        data: dict = Depends(body("api/evidence-result-request.json")),
        child: Child = Depends(device_child),
        s: Session = Depends(db),
    ):
        e = s.get(EvidenceRequest, requestId) if UUID.match(requestId) else None
        if e is None or e.child_id != child.child_id:
            raise KuumaError(404, "not_found", "No such request")
        if data["status"] == "ready" and "sealedEvidence" not in data:
            raise KuumaError(422, "missing_evidence", "Allowed evidence needs its sealed messages")
        e.status, e.completed_at, e.sealed_evidence = data["status"], clock(), data.get("sealedEvidence")
        s.commit()
        audit(f"evidence.{e.status}", actor=child.child_id, resource=e.request_id, service="guardian")
        return evidence_body(e)

    # ------------------------------------------------------------------ child phone

    @app.post("/v1/guardian/pairings", status_code=201)
    def pair(data: dict = Depends(body("api/pairing-request.json")), s: Session = Depends(db)):
        code = s.get(PairingCode, data["pairingCode"])
        if code is None or code.used or aware(code.expires_at) < clock():
            time.sleep(0.2)  # slow down guessing; the gateway rate-limits too
            raise KuumaError(404, "invalid_code", "That pairing code is not valid")
        try:
            key = serialization.load_der_public_key(base64.b64decode(data["devicePublicKey"]))
            if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
                raise ValueError
        except ValueError:
            raise KuumaError(422, "bad_device_key", "devicePublicKey must be ECDSA P-256") from None
        child = s.get(Child, code.child_id)
        code.used = True
        child.paired, child.device_id, child.device_public_key = (
            True,
            str(uuid.uuid4()),
            data["devicePublicKey"],
        )
        child.last_heartbeat_at, child.protection_active = clock(), True
        s.commit()
        publish(
            "guardian.pairing.completed",
            {"childId": child.child_id, "deviceId": child.device_id, "ageBracket": child.age_bracket},
            child.device_id,
        )
        audit("child.paired", actor=child.parent_id, resource=child.child_id, service="guardian")
        return {
            "childId": child.child_id,
            "deviceId": child.device_id,
            "policy": json.loads(child.policy_json),
        }

    @app.post("/v1/guardian/alerts", status_code=201)
    def create_alert(
        data: dict = Depends(body("api/alert-request.json")),
        child: Child = Depends(device_child),
        s: Session = Depends(db),
    ):
        child = s.merge(child)
        a = raise_alert(
            s,
            child,
            kind=data.get("kind", "flag"),
            severity=data["severity"],
            case_id=data["caseId"],
            category=data.get("category"),
            app_source=data.get("appSource"),
            occurred_at=data.get("occurredAt"),
            sealed_excerpt=data.get("sealedExcerpt"),
        )
        return JSONResponse(alert_body(a), status_code=201)

    @app.post("/v1/guardian/approval-requests", status_code=201)
    def create_approval_request(
        data: dict = Depends(body("api/approval-request.json")),
        child: Child = Depends(device_child),
        s: Session = Depends(db),
    ):
        existing = s.scalars(select(Approval).where(Approval.request_id == data["requestId"])).first()
        if existing is not None:
            if existing.child_id != child.child_id:
                raise KuumaError(409, "conflict", "requestId already used")
            return JSONResponse(approval_body(existing), status_code=201)
        a = Approval(
            approval_id=str(uuid.uuid4()),
            request_id=data["requestId"],
            child_id=child.child_id,
            case_id=data["caseId"],
            kind=data["kind"],
            recipient_type=data.get("recipientType"),
            status="pending",
            created_at=clock(),
            delivered=False,
        )
        s.add(a)
        s.commit()
        push.send(child.parent_id, {"approvalId": a.approval_id, "kind": "approval"})
        return JSONResponse(approval_body(a), status_code=201)

    @app.post("/v1/guardian/heartbeat")
    def heartbeat(
        data: dict = Depends(body("api/heartbeat-request.json")),
        child: Child = Depends(device_child),
        s: Session = Depends(db),
    ):
        child = s.merge(child)
        missing = data.get("missingPermissions", [])
        active = data.get("protectionActive", True) and not missing
        child.last_heartbeat_at, child.protection_active, child.missing_permissions = (
            clock(),
            active,
            json.dumps(missing),
        )
        if not active and not child.tamper_open:
            child.tamper_open = True
            raise_alert(s, child, kind="tamper", severity="high", case_id=child.child_id)
        elif active and child.tamper_open:
            child.tamper_open = False
        s.commit()
        out: dict = {"latestPolicyVersion": child.policy_version, "serverTime": iso(clock())}
        if data["policyVersion"] < child.policy_version:
            out["policy"] = json.loads(child.policy_json)
        undelivered = s.scalars(
            select(Approval).where(
                Approval.child_id == child.child_id,
                Approval.status != "pending",
                Approval.delivered.is_(False),
            )
        ).all()
        out["decisions"] = [approval_result(a) for a in undelivered]
        for a in undelivered:
            a.delivered = True
        orders = s.scalars(
            select(Report).where(Report.child_id == child.child_id, Report.delivered.is_(False))
        ).all()
        out["reports"] = [report_order(r) for r in orders]
        for r in orders:
            r.delivered = True
        asks = s.scalars(
            select(EvidenceRequest).where(
                EvidenceRequest.child_id == child.child_id, EvidenceRequest.delivered.is_(False)
            )
        ).all()
        out["evidenceRequests"] = [evidence_order(e) for e in asks]
        for e in asks:
            e.delivered = True
        s.commit()
        return out

    # ------------------------------------------------------------------ tamper sweep

    def sweep() -> int:
        """Raise a tamper alert for every paired phone that stopped checking in. Returns how many."""
        raised = 0
        cutoff = clock() - TAMPER_AFTER
        with sessions() as s:
            for child in s.scalars(
                select(Child).where(Child.paired.is_(True), Child.tamper_open.is_(False))
            ).all():
                last = aware(child.last_heartbeat_at)
                if last is not None and last < cutoff:
                    child.tamper_open = True
                    raise_alert(s, child, kind="tamper", severity="high", case_id=child.child_id)
                    raised += 1
            s.commit()
        return raised

    app.state.sweep = sweep
    if sweep_every:

        def loop() -> None:
            while True:
                time.sleep(sweep_every)
                try:
                    sweep()
                except Exception:  # noqa: BLE001
                    log.error("tamper.sweep_failed")

        threading.Thread(target=loop, name="guardian-sweep", daemon=True).start()
    return app


app = build_app(sweep_every=300 if os.environ.get("REDIS_URL") else None)
