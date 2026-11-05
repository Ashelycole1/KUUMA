"""Decide what a pull request must test (CI steps 4 and 5).

Prints one repo-relative folder per line for `tools/run_tests.py`, or `.` for everything.

  * Contracts, shared packages, fakes, tools or build config changed -> everything. A
    contract change must re-run every publisher's and consumer's contract tests, and
    every build depends on the shared packages.
  * Otherwise -> only the owner folders that changed (modules/<m>, ai, apps/<a>).

    python tools/ci_plan.py <base-ref>      # e.g. origin/main
"""

from __future__ import annotations

import subprocess
import sys

EVERYTHING_PREFIXES = (
    "packages/",
    "fakes/",
    "tools/",
    "gradle/",
    "infra/",
    ".github/",
    "pyproject.toml",
    "uv.lock",
    "package.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "versions.toml",
    "melos.yaml",
    "Makefile",
)


def owner_folder(path: str) -> str | None:
    parts = path.split("/")
    if parts[0] == "modules" and len(parts) > 2:
        return "/".join(parts[:2])
    if parts[0] == "apps" and len(parts) > 2:
        return "/".join(parts[:2])
    if parts[0] == "ai":
        return "ai"
    return None


def plan(changed: list[str]) -> list[str]:
    if any(p.startswith(EVERYTHING_PREFIXES) for p in changed):
        return ["."]
    folders = sorted({f for p in changed if (f := owner_folder(p))})
    return folders


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "origin/main"
    out = subprocess.run(["git", "diff", "--name-only", f"{base}...HEAD"], capture_output=True, text=True)
    if out.returncode != 0:
        print(".")  # can't tell what changed: test everything
        return 0
    changed = [line.strip() for line in out.stdout.splitlines() if line.strip()]
    for folder in plan(changed) or []:
        print(folder)
    return 0


if __name__ == "__main__":
    sys.exit(main())
