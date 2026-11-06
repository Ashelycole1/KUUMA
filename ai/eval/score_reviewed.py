"""Score local model predictions without printing message content or calling a provider."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

LABELS = {"NEEDS_REVIEW", "NO_FLAG"}
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,80}")


def score(fixture: dict, predictions: list[dict]) -> dict:
    cases = fixture["cases"]
    ids = [row["id"] for row in cases]
    if len(ids) != len(set(ids)) or any(not IDENTIFIER.fullmatch(item) for item in ids):
        raise ValueError("Fixture IDs must be unique short identifiers")
    actual = {}
    for row in predictions:
        key = row["id"]
        if key not in ids or key in actual:
            raise ValueError("Prediction IDs must match the fixture exactly once")
        actual[key] = row
    reviewed = [row for row in cases if row.get("review_status") == "reviewed"]
    if any(row.get("expected_label") not in LABELS for row in reviewed):
        raise ValueError("Reviewed cases require a human label")
    correct = false_clear = false_flag = invalid = 0
    for row in reviewed:
        prediction = actual.get(row["id"], {})
        label = prediction.get("label")
        if prediction.get("output_status") != "VALID" or label not in LABELS:
            invalid += 1
            continue
        expected = row["expected_label"]
        correct += label == expected
        false_clear += expected == "NEEDS_REVIEW" and label == "NO_FLAG"
        false_flag += expected == "NO_FLAG" and label == "NEEDS_REVIEW"
    return {
        "total_cases": len(cases),
        "reviewed_cases": len(reviewed),
        "pending_review": len(cases) - len(reviewed),
        "correct": correct,
        "false_clear": false_clear,
        "false_flag": false_flag,
        "not_checked_or_missing": invalid,
        "accuracy_on_reviewed": correct / len(reviewed) if reviewed else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("predictions", type=Path)
    args = parser.parse_args()
    result = score(
        json.loads(args.fixture.read_text(encoding="utf-8")),
        json.loads(args.predictions.read_text(encoding="utf-8")),
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
