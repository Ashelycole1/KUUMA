"""Self-tests for the generated Python contracts and the harness."""

import uuid

import pytest

from kuuma_contracts import EVENTS, HarmCategory, Language, Severity
from kuuma_contracts.models import (
    AppSource,
    SentinelCaptureReady,
    SentinelMessageFlagged,
    SentinelMessageFlaggedPayload,
)
from kuuma_contracts.testing import ContractViolation, assert_consumes, assert_publishes, validate_event

REF = "or1_" + "A" * 43
NOW = "2026-11-05T09:30:00Z"


def flagged() -> SentinelMessageFlagged:
    return SentinelMessageFlagged(
        event_id=str(uuid.uuid4()),
        occurred_at=NOW,
        device_id=str(uuid.uuid4()),
        payload=SentinelMessageFlaggedPayload(
            flag_id=str(uuid.uuid4()),
            message_ref=REF,
            app_source=AppSource.WHATSAPP,
            conversation_ref=REF,
            sender_ref=REF,
            category=HarmCategory.INSULT,
            severity=Severity.HIGH,
            confidence=0.9,
            language=Language.LG,
        ),
    )


def test_twenty_events():
    assert len(EVENTS) == 20


def test_generated_model_matches_schema():
    data = assert_publishes("sentinel", flagged())
    assert data["type"] == "sentinel.message.flagged"
    assert "caseId" not in data  # optional fields are omitted, not null


def test_wrong_publisher_is_rejected():
    with pytest.raises(ContractViolation, match="belongs to sentinel"):
        assert_publishes("guardian", flagged())


def test_bad_payload_is_rejected():
    data = flagged().to_wire()
    data["payload"]["confidence"] = 2
    with pytest.raises(ContractViolation):
        validate_event(data)


def test_unknown_field_is_rejected():
    data = flagged().to_wire()
    data["payload"]["text"] = "should never be here"
    with pytest.raises(ContractViolation):
        validate_event(data)


def test_phone_only_cannot_leave_phone():
    ev = {
        "eventId": str(uuid.uuid4()),
        "type": "sentinel.capture.ready",
        "version": 1,
        "occurredAt": NOW,
        "deviceId": str(uuid.uuid4()),
        "payload": {
            "flagId": str(uuid.uuid4()),
            "conversationRef": REF,
            "capturedAt": NOW,
            "messages": [
                {"messageRef": REF, "senderRef": REF, "text": "x", "sentAt": NOW, "fromChild": False}
            ],
        },
    }
    assert_publishes("sentinel", ev)
    with pytest.raises(ContractViolation, match="phone only"):
        assert_publishes("sentinel", ev, leaving_phone=True)


def test_consume_roundtrip():
    parsed = assert_consumes(flagged().to_wire(), "sentinel.message.flagged")
    assert isinstance(parsed, SentinelMessageFlagged)
    assert EVENTS[("sentinel.capture.ready", 1)].model is SentinelCaptureReady
