"""Versioned prompts from ai/prompts/<id>/<version>.prompt.yaml.

Never edit a released version: add a new file. Every model output carries `<id>@<version>`
so a case file can always say which prompt produced its translations.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class PromptSpec:
    prompt_id: str
    version: str
    model_family: str
    temperature: float
    max_tokens: int
    template: str

    @property
    def ref(self) -> str:
        return f"{self.prompt_id}@{self.version}"

    def render(self, **values: str) -> str:
        return self.template.format(**values)


def default_prompt_dir() -> Path:
    """ai/prompts at the repo root (this file is packages/kuuma-llm/kuuma_llm/prompts.py)."""
    return Path(__file__).resolve().parents[3] / "ai" / "prompts"


class FilePromptRegistry:
    def __init__(self, root: Path | None = None) -> None:
        self.root = root or default_prompt_dir()
        self._specs: dict[tuple[str, str], PromptSpec] = {}
        for f in sorted(self.root.glob("*/*.prompt.yaml")):
            d = yaml.safe_load(f.read_text(encoding="utf-8"))
            spec = PromptSpec(
                prompt_id=str(d["id"]),
                version=str(d["version"]),
                model_family=str(d["model_family"]),
                temperature=float(d["temperature"]),
                max_tokens=int(d["max_tokens"]),
                template=str(d["template"]),
            )
            if spec.prompt_id != f.parent.name or f"{spec.version}.prompt.yaml" != f.name:
                raise ValueError(f"{f}: id/version must match the folder and file name")
            self._specs[(spec.prompt_id, spec.version)] = spec

    def versions(self, prompt_id: str) -> list[str]:
        return sorted((v for (p, v) in self._specs if p == prompt_id), key=int)

    def get(self, prompt_id: str, version: str | None = None) -> PromptSpec:
        if version is None:
            versions = self.versions(prompt_id)
            if not versions:
                raise KeyError(prompt_id)
            version = versions[-1]
        return self._specs[(prompt_id, version)]
