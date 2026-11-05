"""A local model server with an OpenAI-compatible API (Ollama on this PC).

Only text goes over the wire; nothing is logged here. `<think>...</think>` blocks that
reasoning-tuned models emit are removed before the answer is returned.
"""

from __future__ import annotations

import re
import time

import httpx

from kuuma_llm import LlmMessage, LlmRequest, LlmResponse

_THINK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)


class ModelUnavailableError(RuntimeError):
    """The model server is down, the model is not loaded, or it answered with an error."""


class ModelTimeoutError(RuntimeError):
    """The model took longer than the timeout."""


def strip_think(text: str) -> str:
    text = _THINK.sub("", text)
    if "</think>" in text:  # an opening tag the server already dropped
        text = text.split("</think>", 1)[1]
    return text.strip()


class OpenAICompatibleClient:
    """POST {base_url}/chat/completions. `base_url` is e.g. http://localhost:11434/v1."""

    def __init__(
        self, base_url: str, model: str, timeout: float = 180.0, transport: httpx.BaseTransport | None = None
    ):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout
        self._http = httpx.Client(timeout=timeout, transport=transport)

    def complete(self, request: LlmRequest) -> LlmResponse:
        body = {
            "model": self.model,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
            "temperature": request.temperature,
            "max_tokens": request.max_tokens,
            "stream": False,
        }
        try:
            r = self._http.post(f"{self.base_url}/chat/completions", json=body)
        except httpx.TimeoutException:
            raise ModelTimeoutError(self.model) from None
        except httpx.HTTPError:
            raise ModelUnavailableError(self.model) from None
        if r.status_code != 200:
            raise ModelUnavailableError(f"{self.model}: http {r.status_code}")
        try:
            data = r.json()
            text = data["choices"][0]["message"]["content"] or ""
            usage = data.get("usage") or {}
        except (ValueError, KeyError, IndexError, TypeError):
            raise ModelUnavailableError(f"{self.model}: bad response") from None
        return LlmResponse(
            text=strip_think(text),
            model=self.model,
            input_tokens=int(usage.get("prompt_tokens", 0)),
            output_tokens=int(usage.get("completion_tokens", 0)),
        )

    def health(self) -> bool:
        """Server up and the model listed. Never raises."""
        try:
            r = self._http.get(f"{self.base_url}/models", timeout=5.0)
            return r.status_code == 200 and any(m.get("id") == self.model for m in r.json().get("data", []))
        except Exception:  # noqa: BLE001 - health must never raise
            return False


def timed(fn, *args, **kwargs):
    started = time.monotonic()
    out = fn(*args, **kwargs)
    return out, int((time.monotonic() - started) * 1000)


__all__ = [
    "LlmMessage",
    "ModelTimeoutError",
    "ModelUnavailableError",
    "OpenAICompatibleClient",
    "strip_think",
    "timed",
]
