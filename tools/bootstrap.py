"""`make bootstrap`: install toolchains and dependencies for every language, with no manual steps.

Toolchains that are missing are downloaded, at the versions pinned in versions.toml, into
~/.kuuma/toolchains (override with KUUMA_TOOLCHAINS). Nothing is installed system-wide.

  Python 3.12 + uv · JDK 17 · Flutter (with Dart) · Android SDK · Node 22 + pnpm

Docker is the one thing it cannot install; it is needed only for `make dev`.
Runs on the plain Python that ships with your OS (3.9+), before anything else exists.
"""

from __future__ import annotations

import json
import os
import platform
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLCHAINS = Path(os.environ.get("KUUMA_TOOLCHAINS", Path.home() / ".kuuma" / "toolchains"))
SYSTEM = platform.system()  # Windows | Linux | Darwin
ARM = platform.machine().lower() in ("arm64", "aarch64")
WIN = SYSTEM == "Windows"


def pins() -> dict:
    # Tiny TOML reader for versions.toml (tomllib needs Python 3.11; bootstrap must run earlier).
    out: dict[str, dict[str, str]] = {}
    section = None
    for line in (ROOT / "versions.toml").read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if m := re.match(r"^\[([\w-]+)\]$", line):
            section = out.setdefault(m.group(1), {})
        elif (m := re.match(r'^([\w-]+)\s*=\s*"([^"]*)"$', line)) and section is not None:
            section[m.group(1)] = m.group(2)
    return out


V = pins()
TC = V["toolchains"]


def say(msg: str) -> None:
    print(f"\n==> {msg}", flush=True)


def which(name: str, extra: list[Path] = ()) -> str | None:
    for d in extra:
        for ext in (".exe", ".bat", ".cmd", "") if WIN else ("",):
            p = d / (name + ext)
            if p.exists():
                return str(p)
    return shutil.which(name)


def run(
    cmd: list[str],
    cwd: Path | None = None,
    env: dict | None = None,
    check: bool = True,
    input_text: str | None = None,
) -> subprocess.CompletedProcess:
    print("$ " + " ".join(cmd), flush=True)
    return subprocess.run(cmd, cwd=cwd or ROOT, env=env, check=check, text=True, input=input_text)


def download(url: str, dest: Path) -> Path:
    print(f"  downloading {url}", flush=True)
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "kuuma-bootstrap"})
    with urllib.request.urlopen(req) as r, open(dest, "wb") as f:  # noqa: S310 - fixed https URLs
        shutil.copyfileobj(r, f, length=1 << 20)
    return dest


def extract(archive: Path, into: Path) -> None:
    into.mkdir(parents=True, exist_ok=True)
    name = archive.name
    if name.endswith(".zip"):
        if not WIN and shutil.which("unzip"):
            run(["unzip", "-q", "-o", str(archive), "-d", str(into)])  # keeps exec bits
        else:
            with zipfile.ZipFile(archive) as z:
                z.extractall(into)
    else:
        with tarfile.open(archive) as t:
            t.extractall(into)  # noqa: S202 - official toolchain archives


def make_executable(folder: Path) -> None:
    if WIN or not folder.exists():
        return
    for p in folder.iterdir():
        if p.is_file():
            p.chmod(p.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


# ----------------------------------------------------------------------------- toolchains


def ensure_uv() -> str:
    found = which("uv")
    if found:
        return found
    say(f"Installing uv {TC['uv']}")
    run([sys.executable, "-m", "pip", "install", "--user", f"uv=={TC['uv']}"])
    scripts = subprocess.run(
        [
            sys.executable,
            "-c",
            "import sysconfig;print(sysconfig.get_path('scripts', f'{__import__(\"os\").name}_user'))",
        ],
        capture_output=True,
        text=True,
    ).stdout.strip()
    found = which("uv", [Path(scripts)])
    if not found:
        sys.exit("uv was installed but is not on PATH; add your Python user scripts folder to PATH")
    return found


def java_ok(java: str | None) -> bool:
    if not java:
        return False
    out = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
    m = re.search(r'version "(\d+)', out)
    return bool(m and int(m.group(1)) >= int(TC["java"]))


def ensure_jdk() -> Path | None:
    local = TOOLCHAINS / "jdk"
    if java_ok(which("java", [local / "bin"])):
        return local if (local / "bin").exists() else None
    say(f"Installing JDK {TC['java']} (Eclipse Temurin)")
    os_name = {"Windows": "windows", "Linux": "linux", "Darwin": "mac"}[SYSTEM]
    url = f"https://api.adoptium.net/v3/binary/latest/{TC['java']}/ga/{os_name}/{'aarch64' if ARM else 'x64'}/jdk/hotspot/normal/eclipse"
    with tempfile.TemporaryDirectory() as tmp:
        arc = download(url, Path(tmp) / ("jdk.zip" if WIN else "jdk.tar.gz"))
        extract(arc, Path(tmp) / "x")
        top = next((Path(tmp) / "x").iterdir())
        home = top / "Contents" / "Home" if SYSTEM == "Darwin" else top
        shutil.rmtree(local, ignore_errors=True)
        shutil.move(str(home), str(local))
    return local


def ensure_flutter() -> Path:
    local = TOOLCHAINS / "flutter"
    found = which("flutter", [local / "bin"])
    if found:
        ver = subprocess.run(
            [found, "--version", "--machine"], capture_output=True, text=True, shell=WIN
        ).stdout
        try:
            if json.loads(ver).get("frameworkVersion") != TC["flutter"]:
                print(f"  note: Flutter on PATH is not {TC['flutter']}; Kuuma pins {TC['flutter']}")
        except ValueError:
            pass
        return Path(found).parent.parent
    say(f"Installing Flutter {TC['flutter']}")
    base = "https://storage.googleapis.com/flutter_infra_release/releases/stable"
    file = {
        "Windows": f"windows/flutter_windows_{TC['flutter']}-stable.zip",
        "Linux": f"linux/flutter_linux_{TC['flutter']}-stable.tar.xz",
        "Darwin": f"macos/flutter_macos_{'arm64_' if ARM else ''}{TC['flutter']}-stable.zip",
    }[SYSTEM]
    with tempfile.TemporaryDirectory() as tmp:
        arc = download(f"{base}/{file}", Path(tmp) / Path(file).name)
        extract(arc, TOOLCHAINS)
    return local


def ensure_android_sdk(env: dict) -> Path:
    sdk = Path(
        os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or TOOLCHAINS / "android-sdk"
    )
    sdkmanager = sdk / "cmdline-tools" / "latest" / "bin" / ("sdkmanager.bat" if WIN else "sdkmanager")
    if not sdkmanager.exists():
        say("Installing Android command-line tools")
        os_name = {"Windows": "win", "Linux": "linux", "Darwin": "mac"}[SYSTEM]
        with tempfile.TemporaryDirectory() as tmp:
            arc = download(
                f"https://dl.google.com/android/repository/commandlinetools-{os_name}-11076708_latest.zip",
                Path(tmp) / "cmdline.zip",
            )
            extract(arc, Path(tmp) / "x")
            dest = sdk / "cmdline-tools" / "latest"
            shutil.rmtree(dest, ignore_errors=True)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(Path(tmp) / "x" / "cmdline-tools"), str(dest))
        make_executable(sdkmanager.parent)
    packages = [f"platforms;android-{TC['android-compile-sdk']}", "build-tools;35.0.0", "platform-tools"]
    if not all((sdk / p.replace(";", os.sep)).exists() for p in packages):
        say("Installing Android SDK packages")
        run([str(sdkmanager), f"--sdk_root={sdk}", "--licenses"], env=env, check=False, input_text="y\n" * 20)
        run([str(sdkmanager), f"--sdk_root={sdk}", *packages], env=env)
    return sdk


def ensure_node(env: dict) -> tuple[str, str]:
    local = TOOLCHAINS / "node"
    node = which("node", [local, local / "bin"])
    if node:
        major = int(
            re.sub(
                r"[^\d.]", "", subprocess.run([node, "--version"], capture_output=True, text=True).stdout
            ).split(".")[0]
        )
    if not node or major < int(TC["node"]):
        say(f"Installing Node {TC['node']}")
        ver = "22.15.0"
        file = {
            "Windows": f"node-v{ver}-win-x64.zip",
            "Linux": f"node-v{ver}-linux-{'arm64' if ARM else 'x64'}.tar.xz",
            "Darwin": f"node-v{ver}-darwin-{'arm64' if ARM else 'x64'}.tar.gz",
        }[SYSTEM]
        with tempfile.TemporaryDirectory() as tmp:
            arc = download(f"https://nodejs.org/dist/v{ver}/{file}", Path(tmp) / file)
            extract(arc, Path(tmp) / "x")
            shutil.rmtree(local, ignore_errors=True)
            shutil.move(str(next((Path(tmp) / "x").iterdir())), str(local))
        node = which("node", [local, local / "bin"])
    node_dir = Path(node).parent
    pnpm = which("pnpm", [node_dir])
    if not pnpm:
        say(f"Installing pnpm {TC['pnpm']}")
        npm = which("npm", [node_dir])
        run([npm, "install", "-g", f"pnpm@{TC['pnpm']}"], env=env)
        pnpm = which("pnpm", [node_dir]) or shutil.which("pnpm", path=env["PATH"])
    return node, pnpm


# ----------------------------------------------------------------------------- project setup


def write_env_files(sdk: Path, flutter: Path, jdk: Path | None, extra_paths: list[Path]) -> None:
    kuuma = ROOT / ".kuuma"
    kuuma.mkdir(exist_ok=True)
    lines = [
        f"export ANDROID_HOME := {str(sdk).replace(chr(92), '/')}",
        f"export KUUMA_TOOLCHAINS := {str(TOOLCHAINS).replace(chr(92), '/')}",
    ]
    if jdk:
        lines.append(f"export JAVA_HOME := {str(jdk).replace(chr(92), '/')}")
    # Make runs recipes with sh, which wants ':'-separated POSIX paths even in Git Bash.
    posix = ":".join(_posix(p) for p in extra_paths if p.exists())
    lines.append(f"export PATH := {posix}:$(PATH)")
    (kuuma / "env.mk").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (kuuma / "env.sh").write_text(
        f'export ANDROID_HOME="{_posix(sdk)}"\nexport PATH="{posix}:$PATH"\n', encoding="utf-8"
    )
    local_props = f"sdk.dir={str(sdk).replace(chr(92), '/')}\n"
    (ROOT / "local.properties").write_text(local_props, encoding="utf-8")
    for app in ("child-app", "parent-app"):
        (ROOT / "apps" / app / "android" / "local.properties").write_text(
            local_props + f"flutter.sdk={str(flutter).replace(chr(92), '/')}\n", encoding="utf-8"
        )


def _posix(p: Path) -> str:
    s = str(p).replace("\\", "/")
    if WIN and re.match(r"^[A-Za-z]:/", s):
        s = "/" + s[0].lower() + s[2:]
    return s


def write_dotenv() -> None:
    env = ROOT / ".env"
    if env.exists():
        return
    say("Creating .env from .env.example (local-only random secrets)")
    text = (ROOT / ".env.example").read_text(encoding="utf-8")
    text = re.sub(
        r"(?m)^(KUUMA_CASE_TOKEN_KEY|KUUMA_REF_SALT)=.*$",
        lambda m: f"{m.group(1)}={secrets.token_hex(32)}",
        text,
    )
    env.write_text(text, encoding="utf-8")


def main() -> int:
    print(f"Kuuma bootstrap — toolchains in {TOOLCHAINS}")
    TOOLCHAINS.mkdir(parents=True, exist_ok=True)
    uv = ensure_uv()
    jdk = ensure_jdk()
    flutter_home = ensure_flutter()
    env = dict(os.environ)
    if jdk:
        env["JAVA_HOME"] = str(jdk)
    paths = [flutter_home / "bin", Path(uv).parent] + ([jdk / "bin"] if jdk else [])
    env["PATH"] = os.pathsep.join([str(p) for p in paths] + [env["PATH"]])
    sdk = ensure_android_sdk(env)
    env["ANDROID_HOME"] = str(sdk)
    node, pnpm = ensure_node(env)
    paths += [sdk / "platform-tools", Path(node).parent]
    env["PATH"] = os.pathsep.join([str(p) for p in paths] + [env["PATH"]])
    write_env_files(sdk, flutter_home, jdk, paths)
    write_dotenv()

    flutter = which("flutter", [flutter_home / "bin"])
    say("Configuring Flutter")
    run([flutter, "config", "--no-analytics", "--android-sdk", str(sdk)], env=env, check=False)

    say(f"Python {TC['python']} workspace (uv sync)")
    run([uv, "sync", "--python", TC["python"]], env=env)

    say("TypeScript workspace (pnpm install)")
    run([pnpm, "install", "--frozen-lockfile"], env=env)

    say("Dart/Flutter packages (melos bootstrap)")
    dart = which("dart", [flutter_home / "bin"])
    run([dart, "pub", "get"], env=env)  # root pubspec: melos itself
    if run([dart, "run", "melos", "bootstrap"], env=env, check=False).returncode != 0:
        print("  melos bootstrap failed; falling back to flutter pub get per package")
        for pubspec in sorted(ROOT.glob("**/pubspec.yaml")):
            if ".dart_tool" not in pubspec.parts and pubspec.parent != ROOT:
                run([flutter, "pub", "get"], cwd=pubspec.parent, env=env)

    say("Gradle (downloads Gradle and the Android plugins once)")
    run([str(ROOT / ("gradlew.bat" if WIN else "gradlew")), "--console=plain", "-q", "help"], env=env)

    if (ROOT / ".git").exists():
        say("Git hooks (pre-commit)")
        run([uv, "run", "pre-commit", "install"], env=env, check=False)

    say("Checking generated code is current")
    run([uv, "run", "python", "packages/contracts/codegen/gen.py", "--check"], env=env)

    docker = shutil.which("docker")
    print("\nBootstrap complete.")
    if not docker:
        print("  Docker not found: install Docker Desktop (or Docker Engine) before `make dev`.")
    print("  Next: make dev   (local stack)   ·   make test   ·   make fake SCENARIO=luganda-insults")
    return 0


if __name__ == "__main__":
    sys.exit(main())
