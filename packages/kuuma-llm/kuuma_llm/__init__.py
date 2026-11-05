"""kuuma-llm: interfaces only. The AI owner adds real providers here (and only here or under ai/).

Rule 4: no model calls, prompts or provider SDKs anywhere outside ai/, packages/kuuma-llm
and packages/ai-runtime-android. The boundary check enforces it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True)
class LlmMessage:
    role: str  # "system" | "user" | "assistant"
    content: str


@dataclass(frozen=True)
class LlmRequest:
    prompt_id: str
    prompt_version: str
    messages: list[LlmMessage]
    max_tokens: int = 512
    temperature: float = 0.0
    metadata: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class LlmResponse:
    text: str
    model: str
    input_tokens: int
    output_tokens: int


class LlmClient(Protocol):
    """One call to a language model. Implementations must not log request or response text."""

    def complete(self, request: LlmRequest) -> LlmResponse: ...


@dataclass(frozen=True)
class Prompt:
    prompt_id: str
    version: str
    template: str

    def render(self, **values: str) -> str:
        return self.template.format(**values)


class PromptRegistry(Protocol):
    """Versioned prompts, stored under ai/prompts/."""

    def get(self, prompt_id: str, version: str | None = None) -> Prompt: ...

    def versions(self, prompt_id: str) -> list[str]: ...


class NoModelClient:
    """Default client: refuses to call anything. Swapped for a real provider by the AI owner."""

    model = "none"

    def complete(self, request: LlmRequest) -> LlmResponse:
        raise NotImplementedError(
            "kuuma-llm has no model yet; the AI service stub serves sample data instead"
        )


class InMemoryPromptRegistry:
    def __init__(self, prompts: list[Prompt] | None = None) -> None:
        self._prompts: dict[tuple[str, str], Prompt] = {(p.prompt_id, p.version): p for p in prompts or []}

    def get(self, prompt_id: str, version: str | None = None) -> Prompt:
        if version is None:
            versions = self.versions(prompt_id)
            if not versions:
                raise KeyError(prompt_id)
            version = versions[-1]
        return self._prompts[(prompt_id, version)]

    def versions(self, prompt_id: str) -> list[str]:
        return sorted(v for (pid, v) in self._prompts if pid == prompt_id)
