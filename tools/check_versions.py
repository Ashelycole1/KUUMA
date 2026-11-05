"""Fail if any manifest disagrees with versions.toml (the one place versions are pinned)."""

from __future__ import annotations

import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    v = tomllib.loads((ROOT / "versions.toml").read_text(encoding="utf-8"))
    tc, kt, dart, py, node, img = v["toolchains"], v["kotlin"], v["dart"], v["python"], v["node"], v["images"]
    problems: list[str] = []

    def expect(where: str, actual: str | None, wanted: str) -> None:
        if actual != wanted:
            problems.append(f"{where}: {actual!r} but versions.toml says {wanted!r}")

    cat = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    expect("gradle catalog kotlin", cat.get("kotlin"), tc["kotlin"])
    expect("gradle catalog agp", cat.get("agp"), tc["android-gradle-plugin"])
    expect("gradle catalog compileSdk", cat.get("compileSdk"), tc["android-compile-sdk"])
    expect("gradle catalog minSdk", cat.get("minSdk"), tc["android-min-sdk"])
    expect("gradle catalog targetSdk", cat.get("targetSdk"), tc["android-target-sdk"])
    expect("gradle catalog ktlintGradle", cat.get("ktlintGradle"), kt["ktlint-gradle"])
    for k in (
        "kotlinx-serialization",
        "kotlinx-coroutines",
        "androidx-core",
        "sqlcipher-android",
        "androidx-sqlite",
        "lint",
        "junit",
        "json-schema-validator",
        "ktlint",
    ):
        expect(f"gradle catalog {k}", cat.get(k), kt[k])

    for wrapper in [
        "gradle/wrapper/gradle-wrapper.properties",
        "apps/child-app/android/gradle/wrapper/gradle-wrapper.properties",
        "apps/parent-app/android/gradle/wrapper/gradle-wrapper.properties",
    ]:
        m = re.search(r"gradle-([\d.]+)-bin\.zip", (ROOT / wrapper).read_text(encoding="utf-8"))
        expect(wrapper, m.group(1) if m else None, tc["gradle"])

    for app in ("child-app", "parent-app"):
        s = (ROOT / f"apps/{app}/android/settings.gradle").read_text(encoding="utf-8")
        agp = re.search(r'"com\.android\.application" version "([^"]+)"', s)
        kot = re.search(r'"org\.jetbrains\.kotlin\.android" version "([^"]+)"', s)
        expect(f"{app} AGP", agp.group(1) if agp else None, tc["android-gradle-plugin"])
        expect(f"{app} Kotlin", kot.group(1) if kot else None, tc["kotlin"])
        b = (ROOT / f"apps/{app}/android/app/build.gradle").read_text(encoding="utf-8")
        ms = re.search(r"minSdk = (\d+)", b)
        expect(f"{app} minSdk", ms.group(1) if ms else None, tc["android-min-sdk"])

    pub_pins = {
        "flutter_lints": dart["flutter-lints"],
        "local_auth": dart["local-auth"],
        "json_schema": dart["json-schema"],
        "melos": dart["melos"],
    }
    for pubspec in ROOT.glob("**/pubspec.yaml"):
        if any(p in pubspec.parts for p in (".dart_tool", "build", "node_modules")):
            continue
        text = pubspec.read_text(encoding="utf-8")
        for name, wanted in pub_pins.items():
            m = re.search(rf"^\s+{name}:\s*\^?([\d.]+)\s*$", text, re.M)
            if m:
                expect(f"{pubspec.relative_to(ROOT)} {name}", m.group(1), wanted)

    py_pins = {
        k: py[k]
        for k in (
            "fastapi",
            "pydantic",
            "sqlalchemy",
            "alembic",
            "redis",
            "cryptography",
            "pyyaml",
            "jsonschema",
            "pytest",
            "ruff",
            "httpx",
            "prometheus-client",
            "pre-commit",
        )
    }
    for pp in ROOT.glob("**/pyproject.toml"):
        if any(p in pp.parts for p in (".venv", "node_modules", "build")):
            continue
        text = pp.read_text(encoding="utf-8")
        for name, wanted in py_pins.items():
            for m in re.finditer(rf'"{re.escape(name)}(\[[^\]]*\])?==([^"]+)"', text):
                expect(f"{pp.relative_to(ROOT)} {name}", m.group(2), wanted)

    node_pins = {
        "typescript": node["typescript"],
        "ajv": node["ajv"],
        "ajv-formats": node["ajv-formats"],
        "eslint": node["eslint"],
        "typescript-eslint": node["typescript-eslint"],
        "prettier": node["prettier"],
        "vitest": node["vitest"],
    }
    for pj in ROOT.glob("**/package.json"):
        if "node_modules" in pj.parts or "dist" in pj.parts:
            continue
        data = json.loads(pj.read_text(encoding="utf-8"))
        for section in ("dependencies", "devDependencies"):
            for name, wanted in node_pins.items():
                if name in data.get(section, {}):
                    expect(f"{pj.relative_to(ROOT)} {name}", data[section][name], wanted)
        if pj.parent == ROOT:
            expect("package.json packageManager", data.get("packageManager"), f"pnpm@{tc['pnpm']}")

    compose = (ROOT / "infra/docker-compose.yml").read_text(encoding="utf-8")
    for key in ("postgres", "redis", "minio", "nginx"):
        if img[key] not in compose:
            problems.append(f"infra/docker-compose.yml does not use {img[key]}")
    dockerfile = (ROOT / "infra/docker/service.Dockerfile").read_text(encoding="utf-8")
    if f"FROM {img['python']}" not in dockerfile:
        problems.append(f"service.Dockerfile does not use {img['python']}")
    if f"uv:{tc['uv']}" not in dockerfile:
        problems.append(f"service.Dockerfile does not use uv {tc['uv']}")

    if problems:
        print("Version check FAILED (versions.toml is the source of truth):", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1
    print("versions: all manifests agree with versions.toml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
