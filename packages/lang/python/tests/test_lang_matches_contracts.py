"""Fails if kuuma-lang values and contract enums ever differ."""

import json
import re
from pathlib import Path

import pytest

import kuuma_lang

TYPES = Path(__file__).resolve().parents[3] / "contracts" / "schemas" / "types"
LEXICON = Path(__file__).resolve().parents[2] / "lexicon"

PAIRS = {
    "harm-categories": "harm-category.json",
    "severity": "severity.json",
    "grooming-stages": "grooming-stage.json",
    "age-brackets": "age-bracket.json",
    "recipient-types": "recipient-type.json",
    "languages": "language.json",
}


def schema(file: str) -> dict:
    return json.loads((TYPES / file).read_text(encoding="utf-8"))


@pytest.mark.parametrize("name,file", PAIRS.items())
def test_values_equal_contract_enum(name, file):
    assert kuuma_lang.ids(name) == schema(file)["enum"]


def test_advice_keys_match_pattern_and_categories():
    pattern = re.compile(schema("advice-key.json")["pattern"])
    categories = set(schema("harm-category.json")["enum"])
    for e in kuuma_lang.entries("advice-keys"):
        assert pattern.match(e.id), e.id
        assert set(e.extra["categories"]) <= categories, e.id
        assert e.label["en"], f"{e.id} needs English text"


def test_every_label_has_english():
    for name in kuuma_lang.FILES:
        for e in kuuma_lang.entries(name):
            assert e.label.get("en"), f"{name}:{e.id}"


def test_fallback_to_english():
    assert kuuma_lang.advice_text("advice.threat.tell_adult", "nyn").startswith("Please tell")
    assert kuuma_lang.severity_rank("high") > kuuma_lang.severity_rank("low")


def test_lexicon_files_use_contract_values():
    """The lexicon is the AI owner's; Core only checks it follows the documented format."""
    import yaml

    languages = set(schema("language.json")["enum"])
    for p in LEXICON.glob("*.yaml"):
        doc = yaml.safe_load(p.read_text(encoding="utf-8"))
        assert doc["language"] == p.stem and p.stem in languages
        for entry in doc.get("entries", []):
            assert entry["category"] in schema("harm-category.json")["enum"]
            assert entry["severity"] in schema("severity.json")["enum"]
