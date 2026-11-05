"""`make apk`: build the signed debug child and parent APKs into out/apk/."""

from __future__ import annotations

import shutil
import sys

from kuuma_tools import ROOT, run

APPS = {"child-app": "kuuma-child-debug.apk", "parent-app": "kuuma-parent-debug.apk"}


def main() -> int:
    out = ROOT / "out" / "apk"
    out.mkdir(parents=True, exist_ok=True)
    for app, name in APPS.items():
        run(["flutter", "build", "apk", "--debug"], cwd=ROOT / "apps" / app)
        shutil.copy2(
            ROOT / "apps" / app / "build" / "app" / "outputs" / "flutter-apk" / "app-debug.apk", out / name
        )
    print("\nDebug APKs (signed with infra/keys/kuuma-debug.keystore):")
    for name in APPS.values():
        print(f"  out/apk/{name}")
    print("Install on a phone: adb install -r out/apk/kuuma-child-debug.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())
