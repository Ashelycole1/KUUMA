"""One model slot, two priorities.

Sunflower on one PC answers one request at a time and takes seconds. Requests wait here:
analyse (from Sentinel) before translate (from Casefile). Too many waiting analyses are
refused at once, so the phone keeps its own verdict instead of waiting.

If a caller's budget runs out, the caller gets a timeout but the slot stays taken until the
model actually finishes, so the model never gets two requests at once.
"""

from __future__ import annotations

import asyncio
import heapq
import itertools
from collections.abc import Callable
from typing import TypeVar

T = TypeVar("T")

HIGH, LOW = 0, 1


class QueueFullError(RuntimeError):
    pass


class BudgetExceededError(RuntimeError):
    pass


class ModelGate:
    def __init__(self, high_limit: int = 20) -> None:
        self.high_limit = high_limit
        self._busy = False
        self._waiters: list[tuple[int, int, asyncio.Future]] = []
        self._seq = itertools.count()

    @property
    def waiting_high(self) -> int:
        return sum(1 for p, _, f in self._waiters if p == HIGH and not f.done())

    @property
    def busy(self) -> bool:
        return self._busy

    async def _acquire(self, priority: int) -> None:
        if not self._busy and not self._waiters:
            self._busy = True
            return
        if priority == HIGH and self.waiting_high >= self.high_limit:
            raise QueueFullError()
        fut = asyncio.get_running_loop().create_future()
        heapq.heappush(self._waiters, (priority, next(self._seq), fut))
        try:
            await fut
        except asyncio.CancelledError:
            if fut.done() and not fut.cancelled():
                self._release()  # we were handed the slot as we gave up: pass it on
            raise

    def _release(self) -> None:
        while self._waiters:
            _, _, fut = heapq.heappop(self._waiters)
            if not fut.done():
                fut.set_result(None)  # the slot passes straight to the next waiter
                return
        self._busy = False

    async def run(self, priority: int, work: Callable[[], T], budget: float) -> T:
        """Runs `work` in a thread when the slot is free. `budget` covers waiting and running."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + budget
        try:
            await asyncio.wait_for(self._acquire(priority), timeout=budget)
        except TimeoutError:
            raise BudgetExceededError() from None
        task = asyncio.ensure_future(asyncio.to_thread(work))
        task.add_done_callback(lambda _: self._release())
        try:
            return await asyncio.wait_for(asyncio.shield(task), timeout=max(0.0, deadline - loop.time()))
        except TimeoutError:
            raise BudgetExceededError() from None
