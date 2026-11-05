"""Companion content packs: validate the source, build the signed archive, describe it.

The source is `content/pack.yaml`. A built pack is:

  pack.json   the content as canonical JSON (sorted keys, no spaces, UTF-8)
  pack.sig    base64 Ed25519 signature over the exact bytes of pack.json

zipped with fixed timestamps, so the same source always gives the same archive and sha256.
The phone checks the signature against the pinned public key before using a downloaded pack.

    uv run python -m kuuma_companion_service.packs build            # build + refresh bundled copy
    uv run python -m kuuma_companion_service.packs build --release  # also require reviewer sign-off
    uv run python -m kuuma_companion_service.packs public-key       # key to pin in the app
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import sys
import zipfile
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from kuuma_contracts.models import AgeBracket, AppSource, HarmCategory, Language, Severity

SERVICE_DIR = Path(__file__).resolve().parent.parent
SOURCE = SERVICE_DIR / "content" / "pack.yaml"
MODULE_DIR = SERVICE_DIR.parent
BUNDLED = MODULE_DIR / "android" / "src" / "main" / "resources" / "kuuma" / "companion" / "content-pack.json"
ARTIFACT_ID = "content-pack"
FORMAT = 1

LANGS = {lang.value for lang in Language}
BRACKETS = {b.value for b in AgeBracket}
CATEGORIES = {c.value for c in HarmCategory}
APPS = {a.value for a in AppSource}
SEVERITIES = [s.value for s in Severity]
ZIP_TIME = (2026, 1, 1, 0, 0, 0)

# DEBUG ONLY. Local and test builds sign with this well-known key; production must set
# COMPANION_PACK_SIGNING_KEY (base64 32-byte Ed25519 seed) and the app pins its public key.
_DEBUG_SEED = hashlib.sha256(b"kuuma-companion-content-pack-DEBUG-ONLY").digest()


class PackError(ValueError):
    """The pack source is invalid."""


@dataclass(frozen=True)
class BuiltPack:
    version: str
    pack_json: bytes
    signature: bytes
    archive: bytes
    languages: list[str]

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.archive).hexdigest()

    @property
    def file_name(self) -> str:
        return f"{ARTIFACT_ID}-{self.version}.zip"

    def manifest(self, base_url: str) -> dict[str, Any]:
        return {
            "artifactId": ARTIFACT_ID,
            "version": self.version,
            "url": f"{base_url.rstrip('/')}/{self.file_name}",
            "sha256": self.sha256,
            "sizeBytes": len(self.archive),
            "languages": self.languages,
        }


# ---- validation -------------------------------------------------------------------------


def _text(where: str, value: Any, *, is_list: bool = False) -> None:
    """A localized value: {lang: {default|<bracket>: str | [str]}} with an English default."""
    if not isinstance(value, dict) or not value:
        raise PackError(f"{where}: expected {{<lang>: {{default: ...}}}}")
    for lang, variants in value.items():
        if lang not in LANGS:
            raise PackError(f"{where}: unknown language {lang!r}")
        if not isinstance(variants, dict) or not variants:
            raise PackError(f"{where}.{lang}: expected {{default: ...}}")
        for variant, text in variants.items():
            if variant != "default" and variant not in BRACKETS:
                raise PackError(f"{where}.{lang}: unknown age bracket {variant!r}")
            ok = (
                isinstance(text, list) and text and all(isinstance(t, str) and t.strip() for t in text)
                if is_list
                else isinstance(text, str) and text.strip()
            )
            if not ok:
                raise PackError(f"{where}.{lang}.{variant}: empty or wrong type")
    if "default" not in value.get("en", {}):
        raise PackError(f"{where}: English default is required (it is the fallback)")


def validate(src: dict[str, Any]) -> None:
    version = str(src.get("version", ""))
    parts = version.split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        raise PackError("version must be MAJOR.MINOR.PATCH")

    keys: set[str] = set()
    for i, card in enumerate(src.get("advice") or []):
        key = card.get("key", "")
        if not key.startswith("advice.") or key in keys:
            raise PackError(f"advice[{i}]: missing, invalid or duplicate key {key!r}")
        keys.add(key)
        _text(key, card.get("text"))
        if "steps" in card:
            _text(f"{key}.steps", card["steps"], is_list=True)
        if card.get("minSeverity", "low") not in SEVERITIES:
            raise PackError(f"{key}: unknown minSeverity")
    if not keys:
        raise PackError("no advice cards")

    by_cat = src.get("byCategory") or {}
    if set(by_cat) != CATEGORIES:
        missing, extra = CATEGORIES - set(by_cat), set(by_cat) - CATEGORIES
        raise PackError(
            f"byCategory must list every harm category (missing {sorted(missing)}, unknown {sorted(extra)})"
        )
    for cat, order in by_cat.items():
        if not order or any(k not in keys for k in order):
            raise PackError(f"byCategory.{cat}: empty or names a card that does not exist")

    for field in ("crisisCategories", "noReplyCategories"):
        if not set(src.get(field) or []) <= CATEGORIES:
            raise PackError(f"{field}: unknown harm category")
    if not src.get("crisisCategories"):
        raise PackError("crisisCategories must not be empty")

    replies = src.get("safeReplies") or {}
    if "en" not in replies or any(lang not in LANGS or not lines for lang, lines in replies.items()):
        raise PackError("safeReplies: English is required and every language needs at least one reply")
    _text("nudge", src.get("nudge"))

    platforms = src.get("platforms") or {}
    if set(platforms) != APPS:
        raise PackError("platforms must cover every app source")
    for app, steps in platforms.items():
        for action in ("block", "report"):
            _text(f"platforms.{app}.{action}", steps.get(action), is_list=True)

    helplines = src.get("helplines") or []
    if not helplines:
        raise PackError("at least one helpline is required")
    for h in helplines:
        if not str(h.get("number", "")).strip() or not h.get("id"):
            raise PackError("helpline needs an id and a number")
        _text(f"helplines.{h['id']}.name", h.get("name"))
        _text(f"helplines.{h['id']}.hours", h.get("hours"))

    for name, value in (src.get("crisis") or {}).items():
        _text(f"crisis.{name}", value)
    for name in ("title", "body", "breathe", "callPrompt"):
        if name not in (src.get("crisis") or {}):
            raise PackError(f"crisis.{name} is required")
    for name, value in (src.get("ui") or {}).items():
        _text(f"ui.{name}", value)

    review = src.get("review") or {}
    if set(review) != BRACKETS:
        raise PackError("review must list every age bracket")


def unapproved_brackets(src: dict[str, Any]) -> list[str]:
    return sorted(b for b, r in (src.get("review") or {}).items() if (r or {}).get("status") != "approved")


def languages_with_text(src: dict[str, Any]) -> list[str]:
    found: set[str] = set()

    def walk(v: Any) -> None:
        if isinstance(v, dict):
            for k, child in v.items():
                if k in LANGS and isinstance(child, dict | list):
                    found.add(k)
                walk(child)
        elif isinstance(v, list):
            for child in v:
                walk(child)

    walk({k: v for k, v in src.items() if k != "review"})
    return [lang.value for lang in Language if lang.value in found]


# ---- build ------------------------------------------------------------------------------


def load_source(path: Path = SOURCE) -> dict[str, Any]:
    src = yaml.safe_load(path.read_text(encoding="utf-8"))
    src["version"] = str(src.get("version", ""))
    validate(src)
    return src


def canonical(obj: Any) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def to_pack(src: dict[str, Any]) -> dict[str, Any]:
    """What the phone reads. `review` stays in the source; the phone gets only a flag."""
    pack = {k: v for k, v in src.items() if k != "review"}
    pack["format"] = FORMAT
    pack["reviewed"] = not unapproved_brackets(src)
    return pack


def signing_key() -> Ed25519PrivateKey:
    raw = os.environ.get("COMPANION_PACK_SIGNING_KEY")
    if raw:
        return Ed25519PrivateKey.from_private_bytes(base64.b64decode(raw))
    if os.environ.get("KUUMA_ENV") == "production":
        raise RuntimeError("COMPANION_PACK_SIGNING_KEY must be set in production")
    return Ed25519PrivateKey.from_private_bytes(_DEBUG_SEED)


def public_key_b64(key: Ed25519PrivateKey | None = None) -> str:
    pub = (key or signing_key()).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return base64.b64encode(pub).decode()


def build(src: dict[str, Any] | None = None, *, release: bool = False) -> BuiltPack:
    src = src or load_source()
    if release and (pending := unapproved_brackets(src)):
        raise PackError(f"not approved by reviewers for: {', '.join(pending)}")
    body = canonical(to_pack(src))
    sig = base64.b64encode(signing_key().sign(body))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in (("pack.json", body), ("pack.sig", sig)):
            info = zipfile.ZipInfo(name, date_time=ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    return BuiltPack(src["version"], body, sig, buf.getvalue(), languages_with_text(src))


@lru_cache(maxsize=1)
def latest() -> BuiltPack:
    return build()


def bundled_json(pack: BuiltPack) -> str:
    """The copy shipped inside the keyboard: readable JSON so diffs are reviewable."""
    return json.dumps(json.loads(pack.pack_json), indent=2, ensure_ascii=False, sort_keys=True) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="packs")
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build", help="validate, build the archive and refresh the bundled copy")
    b.add_argument("--release", action="store_true", help="refuse unless every age bracket is approved")
    b.add_argument("--out", type=Path, default=MODULE_DIR.parent.parent / "out" / "companion")
    sub.add_parser("public-key", help="print the base64 public key the app pins")
    args = ap.parse_args(argv)

    if args.cmd == "public-key":
        print(public_key_b64())
        return 0
    try:
        pack = build(release=args.release)
    except PackError as e:
        print(f"content pack invalid: {e}", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / pack.file_name).write_bytes(pack.archive)
    BUNDLED.parent.mkdir(parents=True, exist_ok=True)
    BUNDLED.write_text(bundled_json(pack), encoding="utf-8", newline="\n")
    print(
        f"built {pack.file_name} sha256={pack.sha256} bytes={len(pack.archive)} languages={','.join(pack.languages)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
