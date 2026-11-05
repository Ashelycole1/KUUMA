"""`make check`: lint and format in every language, boundary check, privacy scan,
version pins and codegen drift. `--fix` applies the formatters instead.
"""

from __future__ import annotations

import argparse
import sys

from kuuma_tools import DART_PACKAGES, ROOT, gradlew, run


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix", action="store_true", help="apply formatters")
    ap.add_argument("--only", choices=["python", "kotlin", "dart", "ts", "guards"], help="one group only")
    args = ap.parse_args()

    def want(group: str) -> bool:
        return args.only in (None, group)

    failures: list[str] = []

    def step(name: str, cmd: list[str], cwd=None) -> None:
        if run(cmd, cwd=cwd, check=False) != 0:
            failures.append(name)

    if want("guards") and not args.fix:
        step("boundary check", ["uv", "run", "python", "tools/boundary_check.py"])
        step(
            "privacy scan",
            ["uv", "run", "python", "tools/privacy_scan.py", "--artifacts", str(ROOT / "out" / "privacy")],
        )
        step("version pins", ["uv", "run", "python", "tools/check_versions.py"])
        step("codegen drift", ["python", "packages/contracts/codegen/gen.py", "--check"])
        step("lang drift", ["uv", "run", "python", "packages/lang/tools/build.py", "--check"])
        step("scenario drift", ["uv", "run", "python", "-m", "kuuma_fakes.build_scenarios", "--check"])

    if want("python"):
        if args.fix:
            step("ruff fix", ["uv", "run", "ruff", "check", "--fix", "."])
            step("ruff format", ["uv", "run", "ruff", "format", "."])
        else:
            step("ruff", ["uv", "run", "ruff", "check", "."])
            step("ruff format", ["uv", "run", "ruff", "format", "--check", "."])

    if want("kotlin"):
        step("ktlint", [gradlew(), "--console=plain", "-q", "ktlintFormat" if args.fix else "ktlintCheck"])

    if want("dart"):
        dirs = [rel for rel, _ in DART_PACKAGES if "generated" not in rel]
        fmt = ["dart", "format", "--line-length", "120"] + (
            [] if args.fix else ["--output=none", "--set-exit-if-changed"]
        )
        step("dart format", fmt + dirs)
        if not args.fix:
            for rel, kind in DART_PACKAGES:
                step(
                    f"analyze {rel}",
                    ["flutter" if kind == "flutter" else "dart", "analyze"],
                    cwd=ROOT / rel,
                )

    if want("ts"):
        if args.fix:
            step("prettier", ["pnpm", "exec", "prettier", "--write", "."])
            step("eslint fix", ["pnpm", "exec", "eslint", "--fix", "."])
        else:
            step("eslint", ["pnpm", "exec", "eslint", "."])
            step("prettier", ["pnpm", "exec", "prettier", "--check", "."])
            step("tsc", ["pnpm", "-r", "run", "typecheck"])

    if failures:
        print("\nmake check FAILED: " + ", ".join(failures), file=sys.stderr)
        return 1
    print("\nmake check: all " + ("formatters applied" if args.fix else "checks passed"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
