"""Base fakes: one per module, each publishing only its own namespace with schema-valid data.

The fakes react to one another the way the real modules will, so a scenario drives the
whole chain:

    message arrives -> FakeSentinel   sentinel.conversation.active (phone only)
                                      sentinel.message.flagged      (if the script expects harm)
                                      sentinel.capture.ready        (phone only)
                    -> FakeCompanion  companion.advice.shown
                    -> FakeCasefile   casefile.evidence.sealed, casefile.case.updated,
                                      casefile.case.escalated (high severity or 3+ incidents)
                    -> FakeGuardian   guardian.alert.sent

Module owners keep their fake working as their real module grows (rule 6: ship a fake).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import uuid
from collections import defaultdict
from collections.abc import Callable

from kuuma_contracts import EVENTS
from kuuma_contracts.testing import ContractViolation, validate_event
from kuuma_fakes.scenario import Scenario, ScriptedMessage

Handler = Callable[[dict], None]
SEVERITY_RANK = {"low": 1, "medium": 2, "high": 3}


def ts(t: dt.datetime) -> str:
    return t.astimezone(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class LocalBus:
    """Laptop twin of the phone event bus: typed by contract, ownership-enforced."""

    def __init__(self, device_id: str):
        self.device_id = device_id
        self.case_id: str | None = None
        self._subs: dict[str, list[Handler]] = defaultdict(list)
        self._any: list[Handler] = []
        self.log: list[dict] = []

    def subscribe(self, event_type: str, handler: Handler) -> None:
        self._subs[event_type].append(handler)

    def subscribe_all(self, handler: Handler) -> None:
        self._any.append(handler)

    def publisher(self, namespace: str) -> Callable[[str, dict, dt.datetime], dict]:
        def publish(event_type: str, payload: dict, at: dt.datetime) -> dict:
            spec = EVENTS.get((event_type, 1))
            if spec is None:
                raise ContractViolation(f"unknown event {event_type}")
            if spec.publisher != namespace:
                raise PermissionError(f"{namespace} may not publish {event_type}")
            ev = {
                "eventId": str(uuid.uuid4()),
                "type": event_type,
                "version": 1,
                "occurredAt": ts(at),
                "deviceId": self.device_id,
                "payload": payload,
            }
            if self.case_id:
                ev["caseId"] = self.case_id
            validate_event(ev)
            self.log.append(ev)
            for h in self._any:
                h(ev)
            for h in list(self._subs[event_type]):
                h(ev)
            return ev

        return publish


class BaseFake:
    namespace = ""

    def __init__(self, bus: LocalBus, scenario: Scenario, enabled: bool = True):
        self.bus, self.scenario, self.enabled = bus, scenario, enabled
        self._publish = bus.publisher(self.namespace)
        self.now = dt.datetime.now(dt.UTC)

    def publish(self, event_type: str, payload: dict) -> dict | None:
        return self._publish(event_type, payload, self.now) if self.enabled else None


class FakeSentinel(BaseFake):
    namespace = "sentinel"

    def on_message(self, m: ScriptedMessage, at: dt.datetime) -> None:
        self.now = at
        s = self.scenario
        flagged = m.expect is not None and m.sender != "child"
        self.publish(
            "sentinel.conversation.active",
            {
                "conversationRef": s.conversation_ref,
                "appSource": s.app_source,
                "hasFlags": flagged,
                **({"topSeverity": m.expect.severity} if flagged else {}),
            },
        )
        if not flagged:
            return
        flag_id = str(uuid.uuid4())
        self.publish(
            "sentinel.message.flagged",
            {
                "flagId": flag_id,
                "messageRef": s.sender_ref(f"msg-{m.index}"),
                "appSource": s.app_source,
                "conversationRef": s.conversation_ref,
                "senderRef": s.sender_ref(m.sender),
                "category": m.expect.category,
                "severity": m.expect.severity,
                "confidence": 0.95,
                "language": s.language,
            },
        )
        if m.stage:
            self.publish(
                "sentinel.flag.updated",
                {
                    "flagId": flag_id,
                    "severity": m.expect.severity,
                    "category": m.expect.category,
                    "groomingSignals": [m.stage],
                },
            )
        self.publish(
            "sentinel.capture.ready",
            {
                "flagId": flag_id,
                "conversationRef": s.conversation_ref,
                "capturedAt": ts(at),
                "messages": [
                    {
                        "messageRef": s.sender_ref(f"msg-{m.index}"),
                        "senderRef": s.sender_ref(m.sender),
                        "senderDisplayName": s.participants[m.sender]["displayName"],
                        "text": m.text,
                        "sentAt": ts(at),
                        "fromChild": False,
                    }
                ],
            },
        )


class FakeCompanion(BaseFake):
    namespace = "companion"
    ADVICE = {
        "threat": "advice.threat.tell_adult",
        "grooming": "advice.grooming.secrets_are_a_warning",
        "sextortion": "advice.sextortion.do_not_pay",
    }

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.bus.subscribe("sentinel.message.flagged", self.on_flag)

    def on_flag(self, ev: dict) -> None:
        self.now = dt.datetime.fromisoformat(ev["occurredAt"])
        p = ev["payload"]
        self.publish(
            "companion.advice.shown",
            {
                "flagId": p["flagId"],
                "adviceKey": self.ADVICE.get(p["category"], "advice.general.do_not_reply"),
            },
        )


class FakeCasefile(BaseFake):
    namespace = "casefile"

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.chain_index = 0
        self.incidents = 0
        self.top = "low"
        self.escalated = False
        self.bus.subscribe("sentinel.capture.ready", self.on_capture)
        self.bus.subscribe("sentinel.message.flagged", self.on_flag)
        self._last_flag: dict | None = None

    def on_flag(self, ev: dict) -> None:
        self._last_flag = ev["payload"]

    def on_capture(self, ev: dict) -> None:
        self.now = dt.datetime.fromisoformat(ev["occurredAt"])
        p, flag = ev["payload"], self._last_flag or {}
        if self.bus.case_id is None:
            self.bus.case_id = str(uuid.uuid4())
        digest = hashlib.sha256(repr((p["flagId"], self.chain_index)).encode()).hexdigest()
        self.publish(
            "casefile.evidence.sealed",
            {
                "itemId": str(uuid.uuid4()),
                "flagId": p["flagId"],
                "hash": digest,
                "chainIndex": self.chain_index,
            },
        )
        self.chain_index += 1
        self.incidents += 1
        sev = flag.get("severity", "medium")
        if SEVERITY_RANK[sev] > SEVERITY_RANK[self.top]:
            self.top = sev
        self.publish(
            "casefile.case.updated",
            {
                "caseId": self.bus.case_id,
                "offenderLabel": "Person A",
                "incidentCount": self.incidents,
                "topSeverity": self.top,
            },
        )
        if not self.escalated and (self.top == "high" or self.incidents >= 3):
            self.escalated = True
            reason = "grooming_detected" if flag.get("category") == "grooming" else "severity_threshold"
            self.publish(
                "casefile.case.escalated",
                {"caseId": self.bus.case_id, "offenderLabel": "Person A", "reason": reason},
            )


class FakeGuardian(BaseFake):
    namespace = "guardian"

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.bus.subscribe("casefile.case.escalated", self.on_escalated)

    def on_escalated(self, ev: dict) -> None:
        self.now = dt.datetime.fromisoformat(ev["occurredAt"])
        self.publish(
            "guardian.alert.sent",
            {"alertId": str(uuid.uuid4()), "caseId": ev["payload"]["caseId"], "severity": "high"},
        )


ALL_FAKES = {
    "sentinel": FakeSentinel,
    "companion": FakeCompanion,
    "casefile": FakeCasefile,
    "guardian": FakeGuardian,
}
