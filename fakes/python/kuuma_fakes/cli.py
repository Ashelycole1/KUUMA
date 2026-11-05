"""`make fake SCENARIO=<name>` entry point.

If a phone or emulator with the child app (debug build) is connected, the scenario is
played ON THE PHONE through the Android fakes, and the events show in the app's debug
console. Otherwise it is played here on the laptop and printed; with the local stack
up (REDIS_URL), cloud-sendable events are also published to Redis Streams.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys

from kuuma_fakes import scenario as scenarios
from kuuma_fakes.runner import console_printer, run

CHILD_APP = "ug.kuuma.child"
RECEIVER = f"{CHILD_APP}/ug.kuuma.child.debug.ScenarioReceiver"
ACTION = "ug.kuuma.debug.PLAY_SCENARIO"


def adb() -> str | None:
    found = shutil.which("adb")
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        for name in ("adb", "adb.exe"):
            p = os.path.join(home, "platform-tools", name)
            if os.path.exists(p):
                return p
    return None


def device_with_child_app() -> str | None:
    tool = adb()
    if not tool:
        return None
    try:
        out = subprocess.run(
            [tool, "shell", "pm", "list", "packages", CHILD_APP], capture_output=True, text=True, timeout=10
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return tool if out.returncode == 0 and f"package:{CHILD_APP}" in out.stdout.split() else None


def play_on_device(tool: str, name: str, speed_ms: int) -> int:
    cmd = [
        tool,
        "shell",
        "am",
        "broadcast",
        "-a",
        ACTION,
        "-n",
        RECEIVER,
        "--es",
        "scenario",
        name,
        "--ei",
        "speedMs",
        str(speed_ms),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
    print(result.stdout.strip() or result.stderr.strip())
    if result.returncode == 0:
        print(f"Playing {name!r} on the phone. Open the child app > Debug console to watch the events.")
    return result.returncode


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="kuuma-fake")
    ap.add_argument("scenario", nargs="?", help=f"one of: {', '.join(scenarios.all_names())}")
    ap.add_argument("--local", action="store_true", help="play on the laptop even if a phone is connected")
    ap.add_argument("--speed", type=float, default=0.4, help="seconds between messages (default 0.4)")
    ap.add_argument("--off", default="", help="comma-separated fakes to switch off, e.g. guardian,casefile")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args(argv)
    if args.list or not args.scenario:
        print("\n".join(scenarios.all_names()))
        return 0 if args.list else 2
    s = scenarios.load(args.scenario)
    if not args.local:
        tool = device_with_child_app()
        if tool:
            return play_on_device(tool, s.name, int(args.speed * 1000))
        print("No phone with the child app connected: playing on the laptop instead.\n")

    cloud = None
    if os.environ.get("REDIS_URL"):
        from kuuma_server_common.service import redis_client
        from kuuma_server_common.streams import StreamPublisher

        r = redis_client()
        cloud = {b: StreamPublisher(r, b) for b in ("companion", "casefile", "guardian")}
        print(f"Forwarding cloud-sendable events to Redis at {os.environ['REDIS_URL']}\n")
    off = {x.strip() for x in args.off.split(",") if x.strip()}
    print(f"Scenario {s.name}: {s.description} ({len(s.messages)} messages, {s.language}, {s.app_source})\n")
    bus = run(
        s,
        enabled={"sentinel", "companion", "casefile", "guardian"} - off,
        speed=args.speed,
        on_event=console_printer(),
        cloud=cloud,
    )
    print(f"\n{len(bus.log)} events, all schema-valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
