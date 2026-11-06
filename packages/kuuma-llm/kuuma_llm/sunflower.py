"""Sunbird AI's Sunflower: Nilinde's language specialist (Luganda, Runyankole, Swahili, English).

It interprets and translates redacted messages. It never decides whether a message is
harmful: the phone's classifier, or later the reasoning model, does that.

Prompts use Sunbird's own short phrasing: Sunflower translates extra instructions as if they
were the message (measured on SURFACE-02), so a message telling it to "ignore your rules" is
translated, not obeyed. Safe by construction: the guard refuses contact details, each message
is flattened to one line, outputs are checked (placeholders kept, non-empty), and nothing here
logs text.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from kuuma_llm import LlmClient, LlmMessage, LlmRequest
from kuuma_llm.cache import ResultCache
from kuuma_llm.ollama import timed
from kuuma_llm.prompts import FilePromptRegistry, PromptSpec
from kuuma_llm.scrub import check_placeholders, check_safe

#: Sunbird's prompts use the English language name, never the ISO code.
LANGUAGE_NAMES = {"en": "English", "lg": "Luganda", "nyn": "Runyankole", "sw": "Swahili"}
MAX_OUTPUT = 4000
_LABEL = re.compile(r"^\s*(translation|english|meaning|answer)\s*:\s*", re.IGNORECASE)


class SunflowerError(RuntimeError):
    """The model answered, but not with something Nilinde can use (empty, placeholders changed...)."""


@dataclass(frozen=True)
class TranslationResult:
    text: str
    source_language: str | None
    target_language: str
    model: str
    prompt_version: str
    latency_ms: int
    machine_translated: bool = True


@dataclass(frozen=True)
class InterpretationResult:
    english_meaning: str
    model: str
    prompt_version: str
    latency_ms: int


#: Sunflower was trained with its own tags and rewrites ours ([CONTACT_1] -> [NAME_1]), so we
#: send its tags and map them back. Tags with no Sunflower equivalent go through unchanged.
_TO_MODEL = {"CONTACT": "NAME", "PHONE": "NUMBER"}
_FROM_MODEL = {v: k for k, v in _TO_MODEL.items()}
_TAG = re.compile(r"\[([A-Z]+)_(\d{1,3})\]")


def to_model_tags(text: str) -> str:
    return _TAG.sub(lambda m: f"[{_TO_MODEL.get(m.group(1), m.group(1))}_{m.group(2)}]", text)


def from_model_tags(text: str) -> str:
    return _TAG.sub(lambda m: f"[{_FROM_MODEL.get(m.group(1), m.group(1))}_{m.group(2)}]", text)


def flatten(text: str) -> str:
    """One line: a message cannot add lines that look like more of the prompt."""
    return " ".join(text.split())


def _clean(text: str) -> str:
    text = _LABEL.sub("", text.strip())
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        text = text[1:-1].strip()
    return text


class SunflowerClient:
    def __init__(
        self, llm: LlmClient, prompts: FilePromptRegistry | None = None, cache: ResultCache | None = None
    ):
        self.llm = llm
        self.prompts = prompts or FilePromptRegistry()
        self.cache = cache

    @property
    def model(self) -> str:
        return getattr(self.llm, "model", "unknown")

    def health(self) -> bool:
        check = getattr(self.llm, "health", None)
        return bool(check()) if check else False

    def _ask(self, spec: PromptSpec, prompt: str) -> tuple[str, int]:
        req = LlmRequest(
            prompt_id=spec.prompt_id,
            prompt_version=spec.version,
            messages=[LlmMessage("user", prompt)],  # Sunbird's system message stays in the Modelfile
            max_tokens=spec.max_tokens,
            temperature=spec.temperature,
        )
        response, ms = timed(self.llm.complete, req)
        text = _clean(response.text)
        if not text or len(text) > MAX_OUTPUT:
            raise SunflowerError("empty or oversized answer")
        return text, ms

    def translate(self, text: str, target: str, source: str | None = None) -> TranslationResult:
        if target not in LANGUAGE_NAMES or (source is not None and source not in LANGUAGE_NAMES):
            raise ValueError("unsupported language")
        check_safe(text)
        spec = self.prompts.get("translate")
        key = ResultCache.key(spec.ref, self.model, f"{source}>{target}", text)
        if self.cache is not None and (hit := self.cache.get(key)) is not None:
            return TranslationResult(hit, source, target, self.model, spec.ref, 0)
        instruction = (
            f"Translate from {LANGUAGE_NAMES[source]} to {LANGUAGE_NAMES[target]}"
            if source
            else f"Translate to {LANGUAGE_NAMES[target]}"
        )
        out, ms = self._ask(spec, spec.render(instruction=instruction, text=to_model_tags(flatten(text))))
        out = from_model_tags(out)
        if not check_placeholders(text, out, exact=True):
            raise SunflowerError("placeholders changed")
        if self.cache is not None:
            self.cache.put(key, out)
        return TranslationResult(out, source, target, self.model, spec.ref, ms)

    def interpret(self, text: str, context: list[str] | None = None) -> InterpretationResult:
        check_safe(text)
        context = [c for c in (context or [])[-5:] if c.strip()]
        for c in context:
            check_safe(c)
        spec = self.prompts.get("interpret")
        joined = "\n".join(context)
        key = ResultCache.key(spec.ref, self.model, joined, text)
        if self.cache is not None and (hit := self.cache.get(key)) is not None:
            return InterpretationResult(hit, self.model, spec.ref, 0)
        lines = "\n".join(to_model_tags(flatten(c)) for c in context)
        ctx = f"Earlier messages in the chat:\n{lines}\n\n" if context else ""
        out, ms = self._ask(spec, spec.render(text=to_model_tags(flatten(text)), context=ctx))
        out = from_model_tags(out)
        if not check_placeholders(text + "\n" + joined, out, exact=False):
            raise SunflowerError("placeholders invented")
        if self.cache is not None:
            self.cache.put(key, out)
        return InterpretationResult(out, self.model, spec.ref, ms)
