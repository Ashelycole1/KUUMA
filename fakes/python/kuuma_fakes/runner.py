"""Scenario runner: plays a scripted chat as if it arrived through WhatsApp/SMS, driving the fakes."""

from __future__ import annotations

import datetime as dt
import sys
import time
from collections.abc import Callable
from typing import IO

from kuuma_contracts import EVENTS
from kuuma_fakes.fakes import ALL_FAKES, FakeSentinel, LocalBus
from kuuma_fakes.scenario import Scenario

SEED_DEVICE = "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b"


def run(
    scenario: Scenario,
    *,
    enabled: set[str] | None = None,
    speed: float = 0.0,
    on_event: Callable[[dict], None] | None = None,
    cloud=None,
) -> LocalBus:
    """Play `scenario`. `speed` = real seconds between messages (0 = as fast as possible).
    `cloud` (optional): dict build -> StreamPublisher; cloud-sendable events are forwarded to it."""
    enabled = set(ALL_FAKES) if enabled is None else enabled
    bus = LocalBus(SEED_DEVICE)
    if on_event:
        bus.subscribe_all(on_event)
    if cloud is not None:

        def forward(ev: dict) -> None:
            spec = EVENTS[(ev["type"], 1)]
            if not spec.phone_only and spec.where != "phone" and spec.publisher in cloud:
                cloud[spec.publisher].publish(ev)

        bus.subscribe_all(forward)
    fakes = {ns: cls(bus, scenario, enabled=ns in enabled) for ns, cls in ALL_FAKES.items()}
    sentinel: FakeSentinel = fakes["sentinel"]  # type: ignore[assignment]
    start = (dt.datetime.now(dt.UTC) - dt.timedelta(days=max(m.day for m in scenario.messages))).replace(
        second=0, microsecond=0
    )
    for m in scenario.messages:
        sentinel.on_message(m, m.sent_at(start))
        if speed:
            time.sleep(speed)
    return bus


def console_printer(out: IO[str] | None = None) -> Callable[[dict], None]:
    """Prints event type and IDs only. Message text is never printed, even though scenarios are invented."""

    def show(ev: dict) -> None:
        stream = out or sys.stdout
        spec = EVENTS[(ev["type"], 1)]
        where = "PHONE ONLY" if spec.phone_only else spec.where.replace("_to_", "->")
        p = ev["payload"]
        detail = " ".join(
            f"{k}={p[k]}"
            for k in (
                "category",
                "severity",
                "topSeverity",
                "adviceKey",
                "incidentCount",
                "reason",
                "chainIndex",
            )
            if k in p
        )
        stream.write(f"{ev['occurredAt']}  {ev['type']:<30} [{where:<14}] {detail}\n")
        stream.flush()

    return show
