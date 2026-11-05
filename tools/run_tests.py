"""`make test`: unit and contract tests for one folder, or everything from the root.

python tools/run_tests.py [PATH]      # PATH defaults to the repo root
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from kuuma_tools import (
    ANDROID_GRADLE,
    DART_PACKAGES,
    GRADLE_PROJECTS,
    PYTHON_TESTS,
    ROOT,
    TS_PACKAGES,
    gradlew,
    run,
)


def inside(target: Path, rel: str) -> bool:
    """True if `rel` (repo-relative) is the target folder, below it, or contains it."""
    p = (ROOT / rel).resolve()
    return p == target or target in p.parents or p in target.parents


def main() -> int:
    target = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT).resolve()
    if target != ROOT and ROOT not in target.parents:
        print(f"{target} is not inside the repo", file=sys.stderr)
        return 2
    ran = 0

    py = [t for t in PYTHON_TESTS if inside(target, t)]
    if py:
        os.environ.setdefault("KUUMA_LOG_FILE", str(ROOT / "out" / "privacy" / "python-tests.jsonl"))
        run(["uv", "run", "pytest", *py])
        ran += 1

    gradle_tasks = []
    for rel, project in GRADLE_PROJECTS.items():
        if inside(target, rel):
            gradle_tasks.append(f"{project}:{'testDebugUnitTest' if project in ANDROID_GRADLE else 'test'}")
    if gradle_tasks:
        run([gradlew(), "--console=plain", "-q", *gradle_tasks])
        ran += 1

    for rel, kind in DART_PACKAGES:
        if inside(target, rel) and (ROOT / rel / "test").exists():
            run(["flutter" if kind == "flutter" else "dart", "test"], cwd=ROOT / rel)
            ran += 1

    filters = [name for rel, name in TS_PACKAGES.items() if inside(target, rel)]
    if filters:
        args = ["pnpm"]
        for f in filters:
            args += ["--filter", f]
        run([*args, "run", "test"])
        ran += 1

    if not ran:
        print("No tests found for this folder.")
    else:
        print("\nAll tests passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
