"""KUUMA_AI_MODE=sunflower with a fake model: endpoints, fallbacks, queue and log safety."""

import asyncio
import io
import json
import threading
import time

import pytest
from fastapi.testclient import TestClient
from kuuma_ai_service.app import build_app
from kuuma_ai_service.gate import HIGH, LOW, BudgetExceededError, ModelGate, QueueFullError

from kuuma_contracts.testing import validate_body
from kuuma_llm import LlmResponse
from kuuma_llm.config import AiConfig
from kuuma_llm.ollama import ModelUnavailableError
from kuuma_llm.sunflower import SunflowerClient
from kuuma_server_common import safe_log

MODEL = "sunflower-14b:q4"
LUGANDA = "Nja kukukuba bw'onodda ku ssomero enkya"
ON_DEVICE = {"harmful": True, "category": "threat", "severity": "medium", "confidence": 0.58}
SURE = {"harmful": True, "category": "threat", "severity": "high", "confidence": 0.95}


class FakeLlm:
    model = MODEL

    def __init__(self, answers=None, up=True, delay=0.0):
        self.answers, self.up, self.delay, self.calls = list(answers or []), up, delay, []

    def complete(self, request):
        if not self.up:
            raise ModelUnavailableError(MODEL)
        time.sleep(self.delay)
        self.calls.append(request.prompt_id)
        text = self.answers.pop(0) if self.answers else "Thank you very much."
        return LlmResponse(text, MODEL, 1, 1)

    def health(self):
        return self.up


def make(llm, **config):
    cfg = AiConfig(mode="sunflower", **config)
    return TestClient(build_app(cfg, SunflowerClient(llm)))


@pytest.fixture
def logs(monkeypatch):
    """Everything the service logs during a test."""
    buf = io.StringIO()
    monkeypatch.setattr(safe_log.get_logger("ai"), "stream", buf)
    return buf


def test_stub_mode_is_the_default():
    app = build_app(AiConfig())
    assert app.state.ai_mode == "stub"
    r = TestClient(app).post("/v1/ai/translate", json={"text": "oli musiru", "from": "lg", "to": "en"})
    assert r.json()["model"] == "stub"


def test_translate(logs):
    llm = FakeLlm(["Thank you", "[CONTACT_1] you are very stupid"])  # first answer is the warm-up
    with make(llm) as c:
        r = c.post("/v1/ai/translate", json={"text": "[CONTACT_1] oli musiru nnyo", "from": "lg", "to": "en"})
        assert r.status_code == 200, r.text
        validate_body("api/translate-response.json", r.json())
        assert r.json()["text"] == "[CONTACT_1] you are very stupid"
        assert r.json()["machineTranslated"] is True and r.json()["promptVersion"] == "translate@1"
        assert c.get("/healthz/model").json()["sunflower"] == "up"
    assert "musiru" not in logs.getvalue() and "stupid" not in logs.getvalue()


def test_translate_refuses_contact_details():
    with make(FakeLlm()) as c:
        # Two layers: the shared server guard (personal_data_rejected), then kuuma-llm's (unsafe_input).
        r = c.post("/v1/ai/translate", json={"text": "call 0772 123 456", "from": "lg", "to": "en"})
        assert r.status_code == 422 and r.json()["error"]["code"] in (
            "personal_data_rejected",
            "unsafe_input",
        )


def test_translate_when_the_model_is_down():
    with make(FakeLlm(up=False)) as c:
        r = c.post("/v1/ai/translate", json={"text": "oli musiru", "from": "lg", "to": "en"})
        assert r.status_code == 503 and r.json()["error"]["code"] == "model_unavailable"
        assert c.get("/healthz/model").json()["sunflower"] == "down"


def test_translate_unusable_answer_is_never_returned():
    with make(FakeLlm(["Thank you", "you are stupid"])) as c:  # dropped [CONTACT_1]
        r = c.post("/v1/ai/translate", json={"text": "[CONTACT_1] oli musiru", "from": "lg", "to": "en"})
        assert r.status_code == 503


def test_analyse_adds_an_interpretation(logs):
    llm = FakeLlm(["Thank you", "I will beat you when you come to school tomorrow."])
    with make(llm) as c:
        r = c.post(
            "/v1/ai/analyse",
            json={
                "text": LUGANDA,
                "language": "lg",
                "onDevice": ON_DEVICE,
                "context": ["[CONTACT_1]: tukulaba enkya"],
            },
        )
        assert r.status_code == 200, r.text
        body = r.json()
        validate_body("api/analyse-response.json", body)
        # The phone was unsure: the meaning decides.
        assert body["verdictSource"] == "reasoning_model"
        assert body["verdict"]["harmful"] and body["verdict"]["category"] == "threat"
        assert body["interpretation"]["englishMeaning"].startswith("I will beat you")
    assert "kukuba" not in logs.getvalue() and "beat" not in logs.getvalue()


def test_analyse_skips_sunflower_for_plain_english_the_phone_is_sure_of():
    llm = FakeLlm()
    with make(llm) as c:
        c.app.state.warm_up and asyncio.run(asyncio.sleep(0))
        calls = len(llm.calls)
        r = c.post("/v1/ai/analyse", json={"text": "i will kill you", "language": "en", "onDevice": SURE})
        assert r.status_code == 200 and "interpretation" not in r.json()
        assert r.json()["verdict"] == SURE and r.json()["verdictSource"] == "on_device"
        assert len(llm.calls) == calls


def test_a_sure_on_device_verdict_is_never_overturned():
    llm = FakeLlm(["Thank you", "The sender greets you warmly."])
    with make(llm) as c:
        r = c.post("/v1/ai/analyse", json={"text": LUGANDA, "language": "lg", "onDevice": SURE})
        assert r.json()["verdict"] == SURE and "interpretation" in r.json()


def test_unsure_english_is_read_by_sunflower_and_cleared():
    llm = FakeLlm(["Thank you", "The sender says the game was stupid; it is not aimed at anyone."])
    maybe = {"harmful": True, "category": "insult", "severity": "medium", "confidence": 0.45}
    with make(llm) as c:
        r = c.post(
            "/v1/ai/analyse", json={"text": "that game was stupid", "language": "en", "onDevice": maybe}
        )
        body = r.json()
        validate_body("api/analyse-response.json", body)
        assert body["verdictSource"] == "reasoning_model" and body["verdict"]["harmful"] is False


def test_analyse_unknown_language_gets_interpreted_and_judged():
    llm = FakeLlm()
    # No language hint, and no marker words: the old gate silently skipped this input.
    with make(llm) as c:
        r = c.post(
            "/v1/ai/analyse",
            json={"text": "Tugende tukole.", "onDevice": ON_DEVICE},
        )
        assert r.status_code == 200, r.text
        response = r.json()
        validate_body("api/analyse-response.json", response)
        assert "interpret" in llm.calls
        assert response["interpretation"]["model"] == MODEL
        # "Thank you very much." is not harmful: the phone's maybe is cleared.
        assert response["verdict"]["harmful"] is False
        assert response["verdictSource"] == "reasoning_model"


def test_analyse_unknown_language_model_failure_preserves_on_device_verdict():
    with make(FakeLlm(up=False)) as c:
        r = c.post(
            "/v1/ai/analyse",
            json={"text": "Tugende tukole.", "onDevice": ON_DEVICE},
        )
        assert r.status_code == 200
        assert r.json()["verdict"] == ON_DEVICE
        assert "interpretation" not in r.json()


def test_analyse_falls_back_to_the_on_device_verdict():
    with make(FakeLlm(up=False)) as c:
        r = c.post("/v1/ai/analyse", json={"text": LUGANDA, "language": "lg", "onDevice": ON_DEVICE})
        assert r.status_code == 200 and r.json()["verdict"] == ON_DEVICE and "interpretation" not in r.json()


def test_analyse_needs_the_on_device_verdict():
    with make(FakeLlm()) as c:
        r = c.post("/v1/ai/analyse", json={"text": LUGANDA, "language": "lg"})
        assert r.status_code == 503


def test_analyse_refuses_contact_details_in_context():
    with make(FakeLlm()) as c:
        r = c.post(
            "/v1/ai/analyse", json={"text": LUGANDA, "onDevice": ON_DEVICE, "context": ["kato@example.com"]}
        )
        assert r.status_code == 422


# ------------------------------------------------------------------ the gate


def test_gate_runs_one_at_a_time_and_analyse_first():
    async def scenario():
        gate, order, running = ModelGate(), [], []
        lock = threading.Lock()

        def job(name):
            def work():
                with lock:
                    running.append(name)
                    assert len(running) == 1
                time.sleep(0.05)
                with lock:
                    running.remove(name)
                order.append(name)

            return work

        first = asyncio.create_task(gate.run(LOW, job("t1"), 5))
        await asyncio.sleep(0.01)
        low = asyncio.create_task(gate.run(LOW, job("t2"), 5))
        await asyncio.sleep(0.01)
        high = asyncio.create_task(gate.run(HIGH, job("a1"), 5))
        await asyncio.gather(first, low, high)
        return order

    assert asyncio.run(scenario()) == ["t1", "a1", "t2"]


def test_gate_limits_waiting_analyses_and_budgets():
    async def scenario():
        gate = ModelGate(high_limit=1)
        busy = asyncio.create_task(gate.run(LOW, lambda: time.sleep(0.3), 5))
        await asyncio.sleep(0.01)
        waiting = asyncio.create_task(gate.run(HIGH, lambda: "ok", 5))
        await asyncio.sleep(0.01)
        with pytest.raises(QueueFullError):
            await gate.run(HIGH, lambda: "x", 5)
        with pytest.raises(BudgetExceededError):
            await gate.run(LOW, lambda: "y", 0.05)  # cannot even get the slot in time
        assert await waiting == "ok"
        await busy
        with pytest.raises(BudgetExceededError):
            await gate.run(HIGH, lambda: time.sleep(0.2), 0.05)
        assert gate.busy  # slot stays taken until the model really finishes
        await asyncio.sleep(0.25)
        assert not gate.busy

    asyncio.run(scenario())


def test_logs_are_json_without_text(logs):
    with make(FakeLlm(["Thank you", "you are stupid"])) as c:
        c.post("/v1/ai/translate", json={"text": "oli musiru", "from": "lg", "to": "en"})
    for line in logs.getvalue().splitlines():
        json.loads(line)
