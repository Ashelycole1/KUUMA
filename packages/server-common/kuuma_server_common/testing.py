"""Test helpers for services: an in-memory Redis Streams stand-in and a signed test client."""

from __future__ import annotations

import itertools
import json
from collections import defaultdict
from typing import Any

from kuuma_server_common.auth import sign_request
from kuuma_server_common.seed import seed, test_device_private_key


class MemoryStreams:
    """Just enough of redis-py's Streams API for StreamPublisher/StreamConsumer tests."""

    def __init__(self) -> None:
        self.streams: dict[str, list[tuple[str, dict[str, Any]]]] = defaultdict(list)
        self.groups: dict[tuple[str, str], int] = {}
        self._ids = itertools.count(1)

    def xadd(self, name, fields, maxlen=None, approximate=True):
        msg_id = f"{next(self._ids)}-0"
        self.streams[name].append((msg_id, dict(fields)))
        if maxlen:
            del self.streams[name][:-maxlen]
        return msg_id

    def xgroup_create(self, name, groupname, id="$", mkstream=False):
        if (name, groupname) in self.groups:
            raise RuntimeError("BUSYGROUP Consumer Group name already exists")
        self.groups[(name, groupname)] = 0 if id == "0" else len(self.streams[name])

    def xreadgroup(self, groupname, consumername, streams, count=None, block=None):
        out = []
        for name in streams:
            pos = self.groups[(name, groupname)]
            msgs = self.streams[name][pos : pos + (count or 10**9)]
            self.groups[(name, groupname)] = pos + len(msgs)
            if msgs:
                out.append((name, msgs))
        return out

    def xack(self, name, groupname, *ids):
        return len(ids)

    def events(self, event_type: str) -> list[dict]:
        return [json.loads(f["event"]) for _, f in self.streams["kuuma.events." + event_type]]


def signed_headers(method: str, path: str, body: bytes = b"") -> dict[str, str]:
    """Headers signed with the seed test device key."""
    return sign_request(test_device_private_key(), method, path, body, seed()["child_device"]["device_id"])
