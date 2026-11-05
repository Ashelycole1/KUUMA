"""Boundary check (rules 1 and 4).

1. No cross-module imports: modules/<m>/ may use shared packages, never another module.
   Shared code (packages/, fakes/) may not depend on any module either; only the app
   shells (apps/) put modules together.
4. AI lives only in AI folders: no model files, prompt files or LLM/ML SDKs outside
   ai/, packages/kuuma-llm/ and packages/ai-runtime-android/.

  python tools/boundary_check.py [--root DIR]
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

MODULES = ("sentinel", "companion", "casefile", "guardian")
AI_DIRS = ("ai/", "packages/kuuma-llm/", "packages/ai-runtime-android/")
SKIP_DIRS = {
    ".git",
    "node_modules",
    ".dart_tool",
    "build",
    ".gradle",
    ".venv",
    "__pycache__",
    "dist",
    ".kotlin",
    ".idea",
    "generated",
    ".pytest_cache",
    ".ruff_cache",
}
SOURCE_EXT = {
    ".kt",
    ".kts",
    ".java",
    ".dart",
    ".py",
    ".ts",
    ".tsx",
    ".js",
    ".gradle",
    ".yaml",
    ".yml",
    ".toml",
    ".json",
}

MODEL_EXT = {
    ".tflite",
    ".onnx",
    ".gguf",
    ".ggml",
    ".pt",
    ".pth",
    ".safetensors",
    ".h5",
    ".keras",
    ".pb",
    ".mlmodel",
    ".ckpt",
    ".task",
}
PROMPT_FILE = re.compile(r"(\.prompt(\.ya?ml|\.md|\.txt|\.json)?$)|(^|/)prompts/")

AI_SDK = re.compile(
    r"""(?x)
    ^\s*(import|from)\s+(anthropic|openai|google\.generativeai|vertexai|langchain\w*|llama_index|transformers
        |torch|tensorflow|tflite_runtime|onnxruntime|llama_cpp|cohere|mistralai|litellm|sentence_transformers)\b
  | ^\s*import\s+(org\.tensorflow\.lite|com\.google\.mediapipe|ai\.onnxruntime|com\.google\.ai\.client
        |com\.aallam\.openai|com\.anthropic)\b
  | package:(google_generative_ai|tflite_flutter|dart_openai|langchain\w*|onnxruntime\w*|anthropic_sdk_dart)/
  | ["'](openai|@anthropic-ai/sdk|@google/generative-ai|@tensorflow/tfjs[\w-]*|onnxruntime-[\w-]+|langchain)["']
  | ["'](org\.tensorflow:tensorflow-lite[\w-]*|com\.microsoft\.onnxruntime:[\w-]+|com\.google\.mediapipe:[\w-]+
        |com\.google\.ai\.client\.generativeai:[\w-]+)
  | ^\s*["']?(anthropic|openai|torch|transformers|tensorflow|langchain|onnxruntime|llama-cpp-python)\s*[=<>~!"']
  | ^\s*(tflite_flutter|google_generative_ai|dart_openai):
    """
)


def module_refs(other: str) -> re.Pattern:
    return re.compile(
        rf"""(?x)
        ^\s*import\s+ug\.kuuma\.{other}\b
      | package:kuuma_{other}/
      | ^\s*kuuma_{other}\s*:
      | ^\s*(import|from)\s+kuuma_{other}_service\b
      | project\(\s*["']:{other}["']\s*\)
      | ["']ug\.kuuma:{other}:
      | ["']@kuuma/{other}-
      | ["']kuuma-{other}-service["']
        """
    )


REFS = {m: module_refs(m) for m in MODULES}


@dataclass(frozen=True)
class Violation:
    path: str
    line: int
    rule: str
    detail: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: [{self.rule}] {self.detail}"


def owner_of(rel: str) -> str | None:
    parts = rel.split("/")
    if parts[0] == "modules" and len(parts) > 1 and parts[1] in MODULES:
        return parts[1]
    if parts[0] in ("packages", "fakes"):
        return "shared"
    return None  # apps/, ai/, infra/, tools/ ... may compose modules


def walk(root: Path):
    for p in root.rglob("*"):
        rel_parts = p.relative_to(root).parts
        if any(part in SKIP_DIRS for part in rel_parts[:-1]) or p.is_dir():
            continue
        yield p, "/".join(rel_parts)


def check(root: Path) -> list[Violation]:
    out: list[Violation] = []
    for path, rel in walk(root):
        in_ai = rel.startswith(AI_DIRS)
        if rel.startswith("tools/") or "/tests/fixtures/" in rel:
            continue
        if not in_ai:
            if path.suffix.lower() in MODEL_EXT:
                out.append(Violation(rel, 0, "ai-only", f"model file outside the AI folders ({path.suffix})"))
                continue
            if PROMPT_FILE.search(rel):
                out.append(Violation(rel, 0, "ai-only", "prompt file outside the AI folders"))
                continue
        if path.suffix not in SOURCE_EXT and path.name not in (
            "pubspec.yaml",
            "pyproject.toml",
            "package.json",
        ):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        owner = owner_of(rel)
        for n, line in enumerate(text.splitlines(), 1):
            if not in_ai and AI_SDK.search(line):
                out.append(
                    Violation(
                        rel, n, "ai-only", f"AI/ML SDK used outside the AI folders: {line.strip()[:80]}"
                    )
                )
            if owner is None:
                continue
            for m, pattern in REFS.items():
                if m == owner:
                    continue
                if pattern.search(line):
                    who = "shared code" if owner == "shared" else f"module {owner}"
                    out.append(
                        Violation(
                            rel, n, "no-cross-module", f"{who} depends on module {m}: {line.strip()[:80]}"
                        )
                    )
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = ap.parse_args(argv)
    violations = check(Path(args.root))
    if violations:
        print("Boundary check FAILED:", file=sys.stderr)
        for v in violations:
            print(f"  {v}", file=sys.stderr)
        print("\nSee CONTRIBUTING.md rules 1 and 4.", file=sys.stderr)
        return 1
    print("boundary check: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
