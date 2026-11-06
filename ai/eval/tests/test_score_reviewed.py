import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "score_reviewed", Path(__file__).parents[1] / "score_reviewed.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_pending_review_is_never_reported_as_accuracy():
    result = module.score({"cases": [{"id": "lg-example", "review_status": "pending"}]}, [])
    assert result["accuracy_on_reviewed"] is None
    assert result["pending_review"] == 1


def test_missing_or_invalid_output_counts_as_failure_not_safe():
    fixture = {
        "cases": [
            {"id": "one", "review_status": "reviewed", "expected_label": "NEEDS_REVIEW"},
            {"id": "two", "review_status": "reviewed", "expected_label": "NO_FLAG"},
        ]
    }
    result = module.score(fixture, [{"id": "one", "label": "NO_FLAG", "output_status": "MODEL_UNAVAILABLE"}])
    assert result["not_checked_or_missing"] == 2
    assert result["accuracy_on_reviewed"] == 0
    assert result["false_clear"] == 0


def test_duplicate_predictions_are_rejected():
    fixture = {"cases": [{"id": "one", "review_status": "pending"}]}
    with pytest.raises(ValueError):
        module.score(fixture, [{"id": "one"}, {"id": "one"}])
