"""`make dev`: start (or stop, or tail) the local stack with Docker Compose."""

from __future__ import annotations

import shutil
import sys
import time
import urllib.request

from kuuma_tools import ROOT, run

COMPOSE = [
    "docker",
    "compose",
    "-f",
    str(ROOT / "infra" / "docker-compose.yml"),
    "--env-file",
    str(ROOT / ".env"),
]
GATEWAY = "http://localhost:8080"


def wait_for_gateway(timeout: int = 240) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(GATEWAY + "/v1/verify/" + "0" * 64, timeout=3) as r:  # noqa: S310
                if r.status == 200:
                    return True
        except OSError:
            time.sleep(1)  # not up yet
        time.sleep(3)
    return False


def main() -> int:
    action = sys.argv[1] if len(sys.argv) > 1 else "up"
    if not shutil.which("docker"):
        print("Docker is needed for the local stack: https://docs.docker.com/get-docker/", file=sys.stderr)
        return 1
    if not (ROOT / ".env").exists():
        print("No .env yet: run `make bootstrap` first (it creates one from .env.example).", file=sys.stderr)
        return 1
    if action == "down":
        return run([*COMPOSE, "down"], check=False)
    if action == "logs":
        return run([*COMPOSE, "logs", "-f", "--tail", "100"], check=False)
    # The gateway serves Casefile's verification page from its built files.
    run(["pnpm", "--filter", "@kuuma/casefile-web", "run", "build"], check=False)
    run([*COMPOSE, "up", "--build", "-d", "--wait"], check=False)
    if not wait_for_gateway():
        print("The gateway did not come up. See `make dev-logs`.", file=sys.stderr)
        return 1
    print(f"""
Nilinde local stack is up.
  Gateway        {GATEWAY}            (every /v1/... API)
  API docs       {GATEWAY}/v1/...     each service also serves /docs inside the stack
  MinIO console  http://localhost:9001  (user/password in .env)
  PostgreSQL     localhost:5432 db=kuuma (one schema per build)
  Redis          localhost:6379        (event streams: kuuma.events.<type>)

Try:  curl {GATEWAY}/v1/guardian/alerts
      make fake SCENARIO=luganda-insults
Stop: make dev-down""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
