"""Generate contract code for Kotlin, Dart, Python and TypeScript.

    python packages/contracts/codegen/gen.py          # write packages/contracts/generated/
    python packages/contracts/codegen/gen.py --check  # fail if generated/ is out of date (CI drift check)

Standard library only, so it runs before `make bootstrap` has installed anything.
"""

from __future__ import annotations

import argparse
import filecmp
import shutil
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from emit import HEADER, emit_dart, emit_kotlin, emit_python, emit_ts  # noqa: E402
from model import ContractError, load  # noqa: E402

CONTRACTS = HERE.parent
GENERATED = CONTRACTS / "generated"
TEMPLATES = HERE / "templates"
LANGS = {"kotlin": emit_kotlin, "python": emit_python, "ts": emit_ts, "dart": emit_dart}


def render(target: Path) -> None:
    model = load()
    for lang, emitter in LANGS.items():
        out_dir = target / lang
        tpl = TEMPLATES / lang
        if tpl.exists():
            shutil.copytree(tpl, out_dir, dirs_exist_ok=True)
        for rel, text in emitter(model).items():
            path = out_dir / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
    (target / "README.md").write_text(
        f"# Generated contract code\n\n{HEADER}\n\n{len(model.events)} events, {len(model.types)} types.\n",
        encoding="utf-8",
        newline="\n",
    )


def _ignored(name: str) -> bool:
    return name in {
        "node_modules",
        ".dart_tool",
        "build",
        "__pycache__",
        ".venv",
        "dist",
        "pubspec.lock",
        ".packages",
        "kuuma_contracts.egg-info",
    } or name.endswith(".egg-info")


def tree_diff(a: Path, b: Path, rel: str = "") -> list[str]:
    diffs = []
    names_a = {n for n in (p.name for p in a.iterdir())} if a.exists() else set()
    names_b = {n for n in (p.name for p in b.iterdir())} if b.exists() else set()
    for name in sorted(names_a | names_b):
        if _ignored(name):
            continue
        pa, pb, r = a / name, b / name, f"{rel}{name}"
        if name not in names_b:
            diffs.append(f"stale file (delete or regenerate): {r}")
        elif name not in names_a:
            diffs.append(f"missing: {r}")
        elif pa.is_dir() and pb.is_dir():
            diffs += tree_diff(pa, pb, r + "/")
        elif not filecmp.cmp(pa, pb, shallow=False):
            diffs.append(f"out of date: {r}")
    return diffs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="fail if generated code is out of date")
    args = ap.parse_args()
    try:
        if args.check:
            with tempfile.TemporaryDirectory() as tmp:
                fresh = Path(tmp) / "generated"
                render(fresh)
                diffs = tree_diff(GENERATED, fresh)
            if diffs:
                print("Contract codegen drift — run `make gen` and commit the result:", file=sys.stderr)
                for d in diffs:
                    print(f"  {d}", file=sys.stderr)
                return 1
            print("contracts: generated code is up to date")
            return 0
        for lang in LANGS:
            # Keep tool caches (node_modules, .dart_tool, ...) but drop everything we own.
            d = GENERATED / lang
            if d.exists():
                for child in d.iterdir():
                    if not _ignored(child.name):
                        shutil.rmtree(child) if child.is_dir() else child.unlink()
        render(GENERATED)
        print(
            f"contracts: generated {', '.join(LANGS)} into {GENERATED.relative_to(CONTRACTS.parent.parent)}"
        )
        return 0
    except ContractError as e:
        print(f"contract error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
