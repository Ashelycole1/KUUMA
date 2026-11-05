"""Redis Streams client that enforces topic ownership, like the phone bus does.

Rule 3 (publish only your own topics): a StreamPublisher is bound to one build and
refuses events whose namespace is another build's. Phone-only events are refused
outright: they must never reach the cloud. Every event is validated against its
contract schema before it is written.

Stream names: kuuma.events.<event type>, e.g. kuuma.events.guardian.alert.sent
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from typing import Any, Protocol

from kuuma_contracts import EVENTS, NAMESPACES
from kuuma_contracts.models import ContractModel
from kuuma_contracts.testing import validate_event
from kuuma_server_common.safe_log import get_logger

STREAM_PREFIX = "kuuma.events."


class TopicOwnershipError(PermissionError):
    pass


class PhoneOnlyEventError(PermissionError):
    pass


class RedisLike(Protocol):
    def xadd(
        self, name: str, fields: dict[str, Any], maxlen: int | None = None, approximate: bool = True
    ) -> Any: ...
    def xgroup_create(self, name: str, groupname: str, id: str = "$", mkstream: bool = False) -> Any: ...
    def xreadgroup(
        self,
        groupname: str,
        consumername: str,
        streams: dict[str, str],
        count: int | None = None,
        block: int | None = None,
    ) -> Any: ...
    def xack(self, name: str, groupname: str, *ids: str) -> Any: ...


def stream_name(event_type: str) -> str:
    return STREAM_PREFIX + event_type


class StreamPublisher:
    def __init__(self, redis: RedisLike, build: str, maxlen: int = 100_000):
        if build not in NAMESPACES and build != "ai":
            raise ValueError(f"unknown build {build!r}")
        self.redis, self.build, self.maxlen = redis, build, maxlen
        self.log = get_logger(build)

    def publish(self, event: ContractModel | dict) -> str:
        data = event.to_wire() if isinstance(event, ContractModel) else event
        key = (data.get("type"), data.get("version"))
        spec = EVENTS.get(key)
        if spec is None:
            raise ValueError(f"unknown event {key}")
        if spec.publisher != self.build:
            raise TopicOwnershipError(
                f"{self.build} may not publish {spec.type}; it belongs to {spec.publisher}"
            )
        if spec.phone_only:
            raise PhoneOnlyEventError(f"{spec.type} is phone only and can never be published in the cloud")
        validate_event(data)
        msg_id = self.redis.xadd(
            stream_name(spec.type),
            {"event": json.dumps(data, separators=(",", ":"))},
            maxlen=self.maxlen,
            approximate=True,
        )
        msg_id = msg_id.decode() if isinstance(msg_id, bytes) else str(msg_id)
        self.log.info("stream.published", event_type=spec.type, event_id=data["eventId"])
        return msg_id


class StreamConsumer:
    """Reads events of the given types with a consumer group; validates each one."""

    def __init__(self, redis: RedisLike, build: str, event_types: Iterable[str], consumer: str = "c1"):
        self.redis, self.group, self.consumer = redis, f"{build}-group", consumer
        self.types = list(event_types)
        for t in self.types:
            if (t, 1) not in EVENTS and not any(k[0] == t for k in EVENTS):
                raise ValueError(f"unknown event type {t}")
            try:
                self.redis.xgroup_create(stream_name(t), self.group, id="0", mkstream=True)
            except Exception as e:  # noqa: BLE001 - BUSYGROUP means it already exists
                if "BUSYGROUP" not in str(e):
                    raise

    def poll(self, count: int = 10, block_ms: int = 1000) -> list[dict]:
        streams = {stream_name(t): ">" for t in self.types}
        out = []
        for stream, messages in (
            self.redis.xreadgroup(self.group, self.consumer, streams, count=count, block=block_ms) or []
        ):
            stream = stream.decode() if isinstance(stream, bytes) else stream
            for msg_id, fields in messages:
                raw = fields.get(b"event") or fields.get("event")
                data = json.loads(raw)
                validate_event(data)
                out.append(data)
                self.redis.xack(stream, self.group, msg_id)
        return out
