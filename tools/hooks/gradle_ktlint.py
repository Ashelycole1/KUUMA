"""pre-commit hook: run ktlint through Gradle (works the same on Windows and Unix)."""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
sys.exit(subprocess.call([str(gradlew), "--console=plain", "-q", "ktlintCheck"], cwd=ROOT))
