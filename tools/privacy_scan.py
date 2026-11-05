"""Privacy scan (rule 7: never log message content).

Two scans:

  source     Fails on phone numbers inside logging/print calls anywhere (tests included),
             and on phone-number literals in non-test source.
  artifacts  Fails if logs, analytics exports or test database dumps (collected by CI under
             out/privacy/) contain phone numbers or message-like text.

    python tools/privacy_scan.py                  # source scan of the repo
    python tools/privacy_scan.py --artifacts DIR  # also scan collected artifacts
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {
    ".git",
    "node_modules",
    ".dart_tool",
    "build",
    ".gradle",
    ".venv",
    "__pycache__",
    "dist",
    ".kotlin",
    ".idea",
    ".pytest_cache",
    ".ruff_cache",
    "out",
}
SOURCE_EXT = {".kt", ".kts", ".java", ".dart", ".py", ".ts", ".tsx", ".js", ".sh"}
ARTIFACT_EXT = {".log", ".jsonl", ".json", ".txt", ".sql", ".csv", ".ndjson"}

PHONE = re.compile(r"(?<![\w/.:-])(\+?\d[\d \t().-]{7,}\d)(?![\w/.:-])")
LOG_CALL = re.compile(
    r"""(?x)\b(
        log(ger)?\.(debug|info|warn|warning|error|exception|critical|d|i|w|e|v)
      | Log\.(d|i|w|e|v|wtf)
      | SafeLog\.(d|i|w|e)
      | print(ln)?\s*\(
      | debugPrint\s*\(
      | console\.(log|info|warn|error|debug)
      | Timber\.
      | System\.(out|err)\.print
    )"""
)
TEST_PATH = re.compile(
    r"(^|/)(tests?|src/test|src/androidTest|test_\w+\.py$|\w+_test\.(dart|py)$)|\.test\.ts$"
)
# Files that hold test data about phone numbers on purpose (scanner/guard/redactor self-tests).
ALLOWLIST_FILE = ROOT / "tools" / "privacy_allowlist.txt"

WORD = re.compile(r"[^\W\d_]{2,}", re.UNICODE)


def looks_like_phone(s: str) -> bool:
    return any(9 <= sum(c.isdigit() for c in m.group(1)) <= 15 for m in PHONE.finditer(s))


def message_like(s: str) -> bool:
    """Free text: five or more words of letters, e.g. 'you are so stupid nobody likes you'."""
    return len(WORD.findall(s)) >= 5 and " " in s.strip()


def allowlist() -> set[str]:
    if not ALLOWLIST_FILE.exists():
        return set()
    return {
        ln.strip()
        for ln in ALLOWLIST_FILE.read_text(encoding="utf-8").splitlines()
        if ln.strip() and not ln.startswith("#")
    }


def walk(root: Path, exts: set[str]):
    for p in root.rglob("*"):
        parts = p.relative_to(root).parts
        if p.is_file() and p.suffix in exts and not any(x in SKIP_DIRS for x in parts[:-1]):
            yield p, "/".join(parts)


def scan_source(root: Path) -> list[str]:
    allowed = allowlist()
    problems = []
    for path, rel in walk(root, SOURCE_EXT):
        if rel in allowed or "/generated/" in rel:
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (UnicodeDecodeError, OSError):
            continue
        is_test = bool(TEST_PATH.search(rel))
        for n, line in enumerate(lines, 1):
            if not looks_like_phone(line):
                continue
            if LOG_CALL.search(line):
                problems.append(f"{rel}:{n}: phone number in a log/print call")
            elif not is_test:
                problems.append(f"{rel}:{n}: phone-number literal in source (move test data into tests/)")
    return problems


def _json_strings(value, key=""):
    if isinstance(value, str):
        yield key, value
    elif isinstance(value, dict):
        for k, v in value.items():
            yield from _json_strings(v, k)
    elif isinstance(value, list):
        for v in value:
            yield from _json_strings(v, key)


def scan_artifacts(directory: Path) -> list[str]:
    problems = []
    for path, rel in walk(directory, ARTIFACT_EXT):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for n, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if not stripped:
                continue
            if looks_like_phone(stripped):
                problems.append(f"{rel}:{n}: phone number in a log/dump")
                continue
            try:
                data = json.loads(stripped)
            except ValueError:
                data = None
            values = [v for _, v in _json_strings(data)] if isinstance(data, dict | list) else [stripped]
            if any(message_like(v) for v in values):
                problems.append(f"{rel}:{n}: message-like text in a log/dump")
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(ROOT))
    ap.add_argument("--artifacts", help="directory of collected logs/analytics/test DB dumps")
    ap.add_argument("--no-source", action="store_true")
    args = ap.parse_args(argv)
    problems = [] if args.no_source else scan_source(Path(args.root))
    if args.artifacts and Path(args.artifacts).exists():
        problems += scan_artifacts(Path(args.artifacts))
    if problems:
        print("Privacy scan FAILED:", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print("\nSee CONTRIBUTING.md rule 7. Log IDs, enums, numbers and timings only.", file=sys.stderr)
        return 1
    print("privacy scan: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
