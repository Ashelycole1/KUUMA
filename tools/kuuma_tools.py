"""Shared helpers for the tools/ scripts: where the toolchains are, running commands."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = platform.system() == "Windows"
TOOLCHAINS = Path(os.environ.get("KUUMA_TOOLCHAINS", Path.home() / ".kuuma" / "toolchains"))


def exe(name: str) -> str:
    """Resolve a tool, preferring our toolchain folders, then PATH. Adds .bat/.cmd/.exe on Windows."""
    candidates = [name]
    if WINDOWS:
        candidates = [name + ext for ext in (".exe", ".bat", ".cmd")] + [name]
    extra = [
        TOOLCHAINS / "flutter" / "bin",
        TOOLCHAINS / "android-sdk" / "platform-tools",
        TOOLCHAINS / "jdk" / "bin",
    ]
    for d in extra:
        for c in candidates:
            if (d / c).exists():
                return str(d / c)
    for c in candidates:
        found = shutil.which(c)
        if found:
            return found
    return name


def env() -> dict[str, str]:
    e = dict(os.environ)
    sdk = TOOLCHAINS / "android-sdk"
    if sdk.exists() and "ANDROID_HOME" not in e:
        e["ANDROID_HOME"] = str(sdk)
    jdk = TOOLCHAINS / "jdk"
    if jdk.exists() and "JAVA_HOME" not in e:
        e["JAVA_HOME"] = str(jdk)
    paths = [TOOLCHAINS / "flutter" / "bin", sdk / "platform-tools", jdk / "bin"]
    e["PATH"] = os.pathsep.join([str(p) for p in paths if p.exists()] + [e.get("PATH", "")])
    return e


def run(cmd: list[str], cwd: Path | None = None, check: bool = True, quiet: bool = False) -> int:
    shown = " ".join(str(c) for c in cmd)
    if not quiet:
        print(
            f"$ {shown}"
            + (
                f"   (in {cwd.relative_to(ROOT) if cwd and cwd.is_relative_to(ROOT) else cwd})" if cwd else ""
            ),
            flush=True,
        )
    resolved = [exe(cmd[0]), *cmd[1:]]
    result = subprocess.run(resolved, cwd=cwd or ROOT, env=env())
    if check and result.returncode != 0:
        print(f"FAILED ({result.returncode}): {shown}", file=sys.stderr)
        sys.exit(result.returncode)
    return result.returncode


def gradlew() -> str:
    return str(ROOT / ("gradlew.bat" if WINDOWS else "gradlew"))


# Every Dart/Flutter package, in dependency order.
DART_PACKAGES = [
    ("packages/contracts/generated/dart", "dart"),
    ("packages/lang/dart", "dart"),
    ("packages/ui", "flutter"),
    ("modules/companion/flutter", "flutter"),
    ("modules/casefile/flutter", "flutter"),
    ("modules/guardian/flutter", "flutter"),
    ("apps/child-app", "flutter"),
    ("apps/parent-app", "flutter"),
]

# Gradle project path for each Kotlin folder.
GRADLE_PROJECTS = {
    "packages/contracts": ":contracts",
    "packages/lang/kotlin": ":lang",
    "packages/core-android": ":core-android",
    "packages/core-android-lint": ":core-android-lint",
    "packages/ai-runtime-android": ":ai-runtime-android",
    "fakes/android": ":fakes",
    "modules/sentinel/android": ":sentinel",
    "modules/companion/android": ":companion",
    "modules/casefile/android": ":casefile",
    "modules/guardian/android": ":guardian",
}
ANDROID_GRADLE = {
    ":core-android",
    ":ai-runtime-android",
    ":fakes",
    ":sentinel",
    ":companion",
    ":casefile",
    ":guardian",
}

PYTHON_TESTS = [
    "packages/contracts/tests",
    "packages/contracts/generated/python/tests",
    "packages/lang/python/tests",
    "packages/server-common/tests",
    "packages/kuuma-llm/tests",
    "fakes/python/tests",
    "ai/service/tests",
    "modules/companion/service/tests",
    "modules/casefile/service/tests",
    "modules/guardian/service/tests",
    "tools/tests",
]

TS_PACKAGES = {
    "packages/contracts/generated/ts": "@kuuma/contracts",
    "modules/casefile/web": "@kuuma/casefile-web",
}
