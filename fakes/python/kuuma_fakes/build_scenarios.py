"""Bundle fakes/scenarios/*.yaml as JSON for the Android fakes (part of `make gen`).

python -m kuuma_fakes.build_scenarios [--check]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from kuuma_fakes import scenario as scenarios

OUT = Path(__file__).resolve().parents[2] / "android" / "src" / "main" / "resources" / "kuuma" / "scenarios"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    wanted = {f"{n}.json": scenarios.dump_json(scenarios.load(n)) for n in scenarios.all_names()}
    wanted["index.json"] = "[" + ", ".join(f'"{n}"' for n in scenarios.all_names()) + "]\n"
    existing = {p.name: p.read_text(encoding="utf-8") for p in OUT.glob("*.json")} if OUT.exists() else {}
    if args.check:
        if wanted != existing:
            print("Android scenario bundles are stale — run `make gen`", file=sys.stderr)
            return 1
        print("fakes: scenario bundles up to date")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for name in set(existing) - set(wanted):
        (OUT / name).unlink()
    for name, text in wanted.items():
        (OUT / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"fakes: wrote {len(wanted) - 1} scenario bundles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
