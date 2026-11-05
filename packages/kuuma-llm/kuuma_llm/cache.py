"""Results cache: identical exhibits and forwarded messages are translated once.

Keys are hashes (prompt, version, model, input) and hold no text. Values are model outputs,
which are message content: memory only, with a TTL. A shared store must be private and expire.
"""

from __future__ import annotations

import hashlib
import threading
import time
from collections import OrderedDict


class ResultCache:
    def __init__(self, ttl_seconds: float = 7 * 24 * 3600, max_entries: int = 5000, clock=time.monotonic):
        self.ttl, self.max_entries, self.clock = ttl_seconds, max_entries, clock
        self._data: OrderedDict[str, tuple[float, str]] = OrderedDict()
        self._lock = threading.Lock()

    @staticmethod
    def key(*parts: str) -> str:
        return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()

    def get(self, key: str) -> str | None:
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return None
            if item[0] < self.clock():
                del self._data[key]
                return None
            self._data.move_to_end(key)
            return item[1]

    def put(self, key: str, value: str) -> None:
        with self._lock:
            self._data[key] = (self.clock() + self.ttl, value)
            self._data.move_to_end(key)
            while len(self._data) > self.max_entries:
                self._data.popitem(last=False)

    def __len__(self) -> int:
        return len(self._data)
