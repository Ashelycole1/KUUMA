"""Scenario files: scripted chats in fakes/scenarios/*.yaml. Invented text only."""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
from dataclasses import dataclass, field
from pathlib import Path

import yaml

SCENARIOS = Path(__file__).resolve().parents[2] / "scenarios"
FAKE_SALT = b"kuuma-fake-device-salt"  # the fakes' stand-in for the per-device salt


def opaque_ref(value: str, salt: bytes = FAKE_SALT) -> str:
    """Same construction as kuuma-core-android OpaqueRefs: or1_ + base64url(HMAC-SHA256)."""
    mac = hmac.new(salt, value.encode(), hashlib.sha256).digest()
    return "or1_" + base64.urlsafe_b64encode(mac).rstrip(b"=").decode()


@dataclass(frozen=True)
class Expect:
    category: str
    severity: str


@dataclass(frozen=True)
class ScriptedMessage:
    index: int
    day: int
    time: str
    sender: str
    text: str
    expect: Expect | None = None
    stage: str | None = None

    def sent_at(self, start: dt.datetime) -> dt.datetime:
        h, m = (int(x) for x in self.time.split(":"))
        return (start + dt.timedelta(days=self.day - 1)).replace(hour=h, minute=m, second=0, microsecond=0)


@dataclass(frozen=True)
class Scenario:
    name: str
    description: str
    language: str
    app_source: str
    conversation: str
    participants: dict[str, dict]
    messages: list[ScriptedMessage] = field(default_factory=list)

    @property
    def conversation_ref(self) -> str:
        return opaque_ref(f"{self.app_source}:{self.conversation}")

    def sender_ref(self, sender: str) -> str:
        return opaque_ref(f"{self.app_source}:{self.conversation}:{sender}")

    def to_json(self) -> dict:
        """The form bundled into the Android fakes (they cannot parse YAML on Android 5)."""
        return {
            "name": self.name,
            "description": self.description,
            "language": self.language,
            "appSource": self.app_source,
            "conversation": self.conversation,
            "participants": self.participants,
            "messages": [
                {
                    "day": m.day,
                    "time": m.time,
                    "from": m.sender,
                    "text": m.text,
                    **(
                        {"expect": {"category": m.expect.category, "severity": m.expect.severity}}
                        if m.expect
                        else {}
                    ),
                    **({"stage": m.stage} if m.stage else {}),
                }
                for m in self.messages
            ],
        }


def load(name_or_path: str) -> Scenario:
    path = Path(name_or_path)
    if not path.suffix:
        path = SCENARIOS / f"{name_or_path}.yaml"
    if not path.exists():
        available = ", ".join(sorted(p.stem for p in SCENARIOS.glob("*.yaml")))
        raise FileNotFoundError(f"no scenario {name_or_path!r}; available: {available}")
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    msgs = []
    for i, m in enumerate(doc["messages"]):
        if m["from"] not in doc["participants"]:
            raise ValueError(f"{path.name} message {i}: unknown sender {m['from']}")
        exp = m.get("expect")
        msgs.append(
            ScriptedMessage(
                i,
                int(m["day"]),
                str(m["time"]),
                m["from"],
                m["text"],
                Expect(exp["category"], exp["severity"]) if exp else None,
                m.get("stage"),
            )
        )
    return Scenario(
        doc["name"],
        doc["description"],
        doc["language"],
        doc["appSource"],
        doc["conversation"],
        doc["participants"],
        msgs,
    )


def all_names() -> list[str]:
    return sorted(p.stem for p in SCENARIOS.glob("*.yaml"))


def dump_json(s: Scenario) -> str:
    return json.dumps(s.to_json(), indent=1, ensure_ascii=False, sort_keys=True) + "\n"
