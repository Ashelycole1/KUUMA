"""Self-tests for the CI guards: each must block a deliberate violation (handoff gate)."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import boundary_check  # noqa: E402
import privacy_scan  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


def write(root: Path, rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


# ----------------------------------------------------------------------------- boundary check


def test_repo_passes_boundary_check():
    assert boundary_check.check(REPO) == []


@pytest.mark.parametrize(
    "rel,text",
    [
        ("modules/companion/android/src/main/kotlin/X.kt", "import ug.kuuma.guardian.GuardianModule\n"),
        ("modules/casefile/flutter/lib/x.dart", "import 'package:kuuma_guardian/kuuma_guardian.dart';\n"),
        ("modules/casefile/flutter/pubspec.yaml", "dependencies:\n  kuuma_companion:\n    path: ../x\n"),
        (
            "modules/guardian/service/kuuma_guardian_service/x.py",
            "from kuuma_casefile_service.app import app\n",
        ),
        (
            "modules/sentinel/android/build.gradle.kts",
            'dependencies { implementation(project(":casefile")) }\n',
        ),
        ("packages/core-android/src/main/kotlin/Y.kt", "import ug.kuuma.sentinel.SentinelModule\n"),
    ],
)
def test_cross_module_import_is_blocked(tmp_path, rel, text):
    write(tmp_path, rel, text)
    v = boundary_check.check(tmp_path)
    assert [x.rule for x in v] == ["no-cross-module"], v


def test_own_module_and_shared_imports_are_fine(tmp_path):
    write(
        tmp_path,
        "modules/guardian/android/src/main/kotlin/X.kt",
        "import ug.kuuma.guardian.Thing\nimport ug.kuuma.core.bus.EventBus\nimport ug.kuuma.contracts.Events\n",
    )
    write(tmp_path, "apps/child-app/lib/main.dart", "import 'package:kuuma_guardian/kuuma_guardian.dart';\n")
    assert boundary_check.check(tmp_path) == []


@pytest.mark.parametrize(
    "rel,text",
    [
        ("modules/companion/service/kuuma_companion_service/x.py", "import openai\n"),
        ("modules/companion/service/kuuma_companion_service/y.py", "from anthropic import Anthropic\n"),
        (
            "modules/sentinel/android/build.gradle.kts",
            'implementation("org.tensorflow:tensorflow-lite:2.16.1")\n',
        ),
        ("modules/casefile/flutter/pubspec.yaml", "dependencies:\n  tflite_flutter: 0.11.0\n"),
        ("modules/sentinel/android/src/main/assets/model.tflite", "binary"),
        ("modules/companion/service/prompts/advice.md", "You are a helpful assistant"),
    ],
)
def test_ai_outside_ai_folders_is_blocked(tmp_path, rel, text):
    write(tmp_path, rel, text)
    v = boundary_check.check(tmp_path)
    assert v and all(x.rule == "ai-only" for x in v), v


def test_ai_inside_ai_folders_is_fine(tmp_path):
    write(tmp_path, "ai/service/kuuma_ai_service/llm.py", "import anthropic\n")
    write(tmp_path, "ai/models/harm.tflite", "binary")
    write(tmp_path, "ai/prompts/advice.prompt.yaml", "x")
    write(tmp_path, "packages/kuuma-llm/kuuma_llm/x.py", "import openai\n")
    assert boundary_check.check(tmp_path) == []


# ----------------------------------------------------------------------------- privacy scan


def test_repo_passes_privacy_scan():
    assert privacy_scan.scan_source(REPO) == []


@pytest.mark.parametrize(
    "rel,text",
    [
        (
            "modules/guardian/service/kuuma_guardian_service/x.py",
            'log.info("sms.sent", to="+256772123456")\n',
        ),
        ("modules/sentinel/android/src/main/kotlin/X.kt", 'Log.d("Kuuma", "from 0772 123 456")\n'),
        ("modules/companion/flutter/lib/x.dart", "debugPrint('caller 0772123456');\n"),
        ("modules/guardian/service/tests/test_x.py", 'print("+256 772 123456")\n'),  # even in tests
        ("modules/casefile/web/src/x.ts", 'console.log("+256772123456");\n'),
    ],
)
def test_logged_phone_number_is_blocked(tmp_path, rel, text):
    write(tmp_path, rel, text)
    assert privacy_scan.scan_source(tmp_path)


def test_phone_literal_in_source_is_blocked_but_allowed_in_tests(tmp_path):
    write(tmp_path, "modules/guardian/service/kuuma_guardian_service/x.py", 'DEFAULT = "+256772123456"\n')
    write(tmp_path, "modules/guardian/service/tests/test_x.py", 'NUMBER = "+256772123456"\n')
    problems = privacy_scan.scan_source(tmp_path)
    assert len(problems) == 1 and "kuuma_guardian_service/x.py" in problems[0]


def test_timestamps_and_ids_are_not_phone_numbers(tmp_path):
    write(
        tmp_path,
        "modules/guardian/service/kuuma_guardian_service/x.py",
        'log.info("x", at="2026-11-05T09:30:00Z", id="c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a", size=48213)\n',
    )
    assert privacy_scan.scan_source(tmp_path) == []


def test_artifact_scan(tmp_path):
    logs = tmp_path / "out"
    write(
        logs,
        "ok.jsonl",
        '{"event":"alert.created","severity":"high","alert_id":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"}\n',
    )
    assert privacy_scan.scan_artifacts(logs) == []
    write(logs, "bad.jsonl", '{"event":"x","note":"you are so stupid and nobody likes you"}\n')
    write(logs, "dump.sql", "INSERT INTO t VALUES ('+256 772 123456');\n")
    problems = privacy_scan.scan_artifacts(logs)
    assert len(problems) == 2
