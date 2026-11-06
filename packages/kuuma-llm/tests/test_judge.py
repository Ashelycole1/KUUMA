"""The phase-1 judge over Sunflower's English explanation of a message."""

import pytest

from kuuma_llm.judge import judge


@pytest.mark.parametrize(
    ("meaning", "category"),
    [
        ("You are a very stupid person", "insult"),
        ("The sender is calling you an idiot.", "insult"),
        ("The sender threatens to beat you when you come to school tomorrow.", "threat"),
        ("The sender says they will hurt you if you tell anyone.", "threat"),
        ("The sender calls the recipient a fool and says nobody likes them.", "harassment"),
        ("Keep this our secret and do not tell your parents.", "grooming"),
        ("The sender tells the child to kill herself.", "self_harm_encouragement"),
    ],
)
def test_harmful_meanings(meaning, category):
    v = judge(meaning)
    assert v["harmful"] and v["category"] == category and v["confidence"] >= 0.6


@pytest.mark.parametrize(
    "meaning",
    [
        "This is a friendly greeting asking how you are; there is no threat or insult.",
        "The message says: let us go and work.",
        "I hate this traffic, it is killing me.",
        "The person is asking if you have eaten.",
        "It is not an insult, just a joke between friends.",
        "He says the food was useless.",
        "They say the movie was stupid.",
        "",
    ],
)
def test_harmless_meanings(meaning):
    assert judge(meaning) == {"harmful": False, "confidence": 0.8}
