import pytest

from kuuma_llm import InMemoryPromptRegistry, LlmMessage, LlmRequest, NoModelClient, Prompt


def test_no_model_client_refuses():
    with pytest.raises(NotImplementedError):
        NoModelClient().complete(LlmRequest("p", "1", [LlmMessage("user", "hi")]))


def test_registry_picks_latest():
    reg = InMemoryPromptRegistry([Prompt("advice", "1.0.0", "a"), Prompt("advice", "1.1.0", "b {x}")])
    assert reg.get("advice").render(x="y") == "b y"
    assert reg.get("advice", "1.0.0").template == "a"
