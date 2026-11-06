"""The /v1/ai service.

KUUMA_AI_MODE=stub (the default everywhere): every operation is served from its OpenAPI
example, so no builder needs a model. KUUMA_AI_MODE=sunflower: translate and analyse call
Sunflower on the local Ollama (see ai/local/sunflower/README.md); the rest stay stubs.

Text is transient: it is never stored or logged. Logs carry the prompt, model, latency and
status only.
"""

from __future__ import annotations

import asyncio
import time

from fastapi import Depends, FastAPI, Request

from kuuma_ai_service.gate import HIGH, LOW, BudgetExceededError, ModelGate, QueueFullError
from kuuma_contracts.testing import ContractViolation, validate_body
from kuuma_llm.cache import ResultCache
from kuuma_llm.config import AiConfig
from kuuma_llm.judge import judge
from kuuma_llm.language_gate import needs_sunflower
from kuuma_llm.ollama import ModelTimeoutError, ModelUnavailableError, OpenAICompatibleClient
from kuuma_llm.scrub import UnsafeInputError, check_safe
from kuuma_llm.sunflower import SunflowerClient, SunflowerError
from kuuma_server_common.errors import KuumaError
from kuuma_server_common.safe_log import get_logger
from kuuma_server_common.service import create_stub_service

OPENAPI_FILES = ["ai.yaml"]
REAL_OPERATIONS = {"analyse", "translate"}
HOOKS: dict = {}

log = get_logger("ai")


def body(schema: str):
    async def dependency(request: Request) -> dict:
        try:
            data = await request.json()
            validate_body(schema, data)
        except (ValueError, ContractViolation):
            raise KuumaError(422, "invalid_body", f"Body does not match {schema}") from None
        return data

    return dependency


def _checked(schema: str, data: dict) -> dict:
    """Never return a guess: a response the contract refuses becomes a 503."""
    try:
        validate_body(schema, data)
    except ContractViolation:
        raise KuumaError(503, "model_unavailable", "The model's answer was not usable") from None
    return data


def _prompt(ref: str) -> tuple[str, int]:
    pid, version = ref.split("@")
    return pid, int(version)


def _model_failure(exc: Exception) -> KuumaError:
    if isinstance(exc, QueueFullError):
        return KuumaError(503, "model_busy", "Too many requests are waiting for the model")
    if isinstance(exc, BudgetExceededError | ModelTimeoutError):
        return KuumaError(504, "model_timeout", "The model took too long")
    return KuumaError(503, "model_unavailable", "The model is not available")


MODEL_ERRORS = (QueueFullError, BudgetExceededError, ModelTimeoutError, ModelUnavailableError, SunflowerError)


def install_sunflower(app: FastAPI, sunflower: SunflowerClient, config: AiConfig) -> None:
    gate = ModelGate(high_limit=config.queue_limit)
    app.state.gate = gate
    app.state.sunflower = sunflower
    app.state.model_status = "loading"

    async def warm_up() -> None:
        """Loads the model so the first real message is not slow. Reports up / down."""
        try:
            if not await asyncio.to_thread(sunflower.health):
                app.state.model_status = "down"
                log.warning("ai.model.down", model=sunflower.model)
                return
            await gate.run(
                LOW, lambda: sunflower.translate("Webale nnyo.", "en", "lg"), config.translate_budget
            )
            app.state.model_status = "up"
            log.info("ai.model.up", model=sunflower.model)
        except Exception as e:  # noqa: BLE001 - warm-up failures only change the status
            app.state.model_status = "down"
            log.warning("ai.model.warm_up_failed", model=sunflower.model, error_type=type(e).__name__.lower())

    async def start_warm_up() -> None:
        app.state.warm_up = asyncio.create_task(warm_up())

    app.add_event_handler("startup", start_warm_up)

    @app.get("/healthz/model", include_in_schema=False)
    async def model_health():
        up = await asyncio.to_thread(sunflower.health)
        if not up:
            app.state.model_status = "down"
        elif app.state.model_status == "down":
            app.state.model_status = "up"
        return {
            "sunflower": app.state.model_status,
            "model": sunflower.model,
            "busy": gate.busy,
            "waiting": gate.waiting_high,
        }

    @app.post("/v1/ai/translate")
    async def translate(data: dict = Depends(body("api/translate-request.json"))):
        text, source, target = data["text"], data["from"], data["to"]
        try:
            check_safe(text)
        except UnsafeInputError:
            raise KuumaError(422, "unsafe_input", "Input still contains contact details") from None
        if source == target:
            return _checked(
                "api/translate-response.json",
                {"text": text, "from": source, "to": target, "machineTranslated": False, "latencyMs": 0},
            )
        started = time.monotonic()
        try:
            r = await gate.run(
                LOW, lambda: sunflower.translate(text, target, source), config.translate_budget
            )
        except MODEL_ERRORS as e:
            log.warning("ai.translate.failed", model=sunflower.model, error_type=type(e).__name__.lower())
            raise _model_failure(e) from None
        took = int((time.monotonic() - started) * 1000)
        pid, version = _prompt(r.prompt_version)
        log.info(
            "ai.translate.done",
            model=r.model,
            prompt=pid,
            prompt_version=version,
            latency_ms=took,
            cached=r.latency_ms == 0,
        )
        return _checked(
            "api/translate-response.json",
            {
                "text": r.text,
                "from": source,
                "to": target,
                "machineTranslated": True,
                "model": r.model,
                "promptVersion": r.prompt_version,
                "latencyMs": took,
            },
        )

    @app.post("/v1/ai/analyse")
    async def analyse(data: dict = Depends(body("api/analyse-request.json"))):
        text, language, context = data["text"], data.get("language"), data.get("context") or []
        on_device = data.get("onDevice")
        if on_device is None:
            # Phase 1 has no cloud judge yet: without the phone's verdict there is nothing to return.
            raise KuumaError(503, "model_unavailable", "Send onDevice until the reasoning model is live")
        try:
            check_safe(text)
            for c in context:
                check_safe(c)
        except UnsafeInputError:
            raise KuumaError(422, "unsafe_input", "Input still contains contact details") from None
        started = time.monotonic()
        out: dict = {
            "verdict": on_device,
            "groomingSignals": [],
            "modelVersion": sunflower.model,
            "verdictSource": "on_device",
        }
        interpreted = False
        # The phone was unsure (it asks before flagging) unless it is confident the message is
        # harmful; then its verdict stands and Sunflower only explains the message.
        sure = bool(on_device.get("harmful")) and float(on_device.get("confidence", 0)) >= SURE_HARMFUL
        if needs_sunflower(text, language) or not sure:
            try:
                r = await gate.run(HIGH, lambda: sunflower.interpret(text, context), config.analyse_budget)
                out["interpretation"] = {
                    "englishMeaning": r.english_meaning,
                    "model": r.model,
                    "promptVersion": r.prompt_version,
                }
                interpreted = True
                if not sure:
                    # Decide from what the message means (judge.py), in any language.
                    out["verdict"] = judge(r.english_meaning)
                    out["verdictSource"] = "reasoning_model"
            except MODEL_ERRORS as e:
                # The phone's verdict already stands; the interpretation is a bonus.
                log.warning(
                    "ai.analyse.interpret_failed", model=sunflower.model, error_type=type(e).__name__.lower()
                )
        out["latencyMs"] = int((time.monotonic() - started) * 1000)
        log.info(
            "ai.analyse.done", model=sunflower.model, interpreted=interpreted, latency_ms=out["latencyMs"]
        )
        return _checked("api/analyse-response.json", out)


# An on-device verdict this sure that a message is harmful is kept as it is.
SURE_HARMFUL = 0.9


def build_app(config: AiConfig | None = None, sunflower: SunflowerClient | None = None) -> FastAPI:
    config = config or AiConfig.from_env()
    real = config.mode == "sunflower"
    app = create_stub_service("ai", OPENAPI_FILES, skip=REAL_OPERATIONS if real else set(), hooks=HOOKS)
    app.state.ai_mode = config.mode
    if real:
        sunflower = sunflower or SunflowerClient(
            OpenAICompatibleClient(
                config.sunflower_base_url, config.sunflower_model, config.sunflower_timeout
            ),
            cache=ResultCache(ttl_seconds=config.cache_ttl_days * 24 * 3600),
        )
        install_sunflower(app, sunflower, config)
    log.info("ai.mode", mode=config.mode)
    return app


app = build_app()
