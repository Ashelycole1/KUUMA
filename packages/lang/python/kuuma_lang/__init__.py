"""Loader for kuuma-lang data (bundled from packages/lang/data by `make gen`)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from typing import Any

FILES = (
    "harm-categories",
    "severity",
    "grooming-stages",
    "age-brackets",
    "recipient-types",
    "languages",
    "advice-keys",
)


@dataclass(frozen=True)
class Entry:
    id: str
    label: dict[str, str]
    extra: dict[str, Any]

    def label_for(self, language: str) -> str:
        """Label in `language`, falling back to English."""
        return self.label.get(language) or self.label["en"]


@lru_cache(maxsize=1)
def raw() -> dict[str, Any]:
    return json.loads(resources.files("kuuma_lang").joinpath("lang.json").read_text(encoding="utf-8"))


def entries(name: str) -> list[Entry]:
    if name not in FILES:
        raise KeyError(f"unknown kuuma-lang file {name}")
    out = []
    for v in raw()[name]["values"]:
        label = v.get("label") or v.get("text") or {}
        extra = {k: val for k, val in v.items() if k not in ("id", "label")}
        out.append(Entry(v["id"], label, extra))
    return out


def ids(name: str) -> list[str]:
    return [e.id for e in entries(name)]


def advice_text(key: str, language: str = "en") -> str:
    for v in raw()["advice-keys"]["values"]:
        if v["id"] == key:
            return v["text"].get(language) or v["text"]["en"]
    raise KeyError(key)


def severity_rank(severity: str) -> int:
    for e in entries("severity"):
        if e.id == severity:
            return int(e.extra["rank"])
    raise KeyError(severity)
