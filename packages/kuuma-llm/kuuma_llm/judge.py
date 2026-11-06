"""Reads Sunflower's one-sentence English explanation of a message and decides if it is harmful.

Phase 1, until the reasoning model is live: Sunflower is good at saying what a Luganda, Swahili or
Runyankole message *means* ("You are a very stupid person", "The sender threatens to beat the
child at school"), so a careful rule list over that English is enough to turn "the phone was not
sure" into a decision. Clauses that deny harm ("there is no threat", "it is not an insult") are
ignored, so a friendly greeting stays friendly.

The explanation never leaves this function and is never logged.
"""

from __future__ import annotations

import re

# Most serious first: the first category that matches wins.
_RULES: list[tuple[str, str, re.Pattern[str]]] = [
    (
        "self_harm_encouragement",
        "high",
        r"\b(kill (your|him|her|them)sel(f|ves)|commit suicide|suicide|end (your|his|her|their) life|should die|go (and )?die)\b",
    ),
    (
        "sextortion",
        "high",
        r"\b(leak|share|post|spread)\w*\b.{0,40}\b(nude|naked|private) (photo|picture|pic|image|video)s?",
    ),
    ("sexual_harassment", "high", r"\b(sexual\w*|sexually|nude|naked|sex)\b"),
    (
        "grooming",
        "high",
        r"\b(keep (it|this|that) (a )?secret|our (little )?secret|(do not|don'?t) tell (your )?(parents|mother|father|mum|mom|dad|family|anyone)"
        r"|meet (me |up )?alone|send (me )?(a |some )?(photo|picture|pic)s? of (yourself|you))\b",
    ),
    (
        "threat",
        "high",
        r"\b(threat\w*|kill(ing)? (you|him|her|them|the (child|person|recipient))|murder\w*|stab\w*|shoot\w*"
        r"|(beat|hit|hurt|harm|attack|punch|slap|injure)\w* (you|him|her|them|up|the (child|person|recipient))"
        r"|violen(ce|t)|deal with (you|him|her)|come for (you|him|her)|find (you|him|her)|watch (your|his|her) back)\b",
    ),
    ("hate", "medium", r"\b(hate speech|tribalis\w*|racis\w*|ethnic slur)\b"),
    (
        "harassment",
        "medium",
        r"\b(harass\w*|bully\w*|mock\w*|humiliat\w*|shaming|nobody likes|everyone hates|hates? (you|him|her|them)"
        r"|shut up|go away|leave (the group|us alone))\b",
    ),
    (
        "insult",
        "medium",
        r"\b(insult\w*|offensive|demean\w*|belittl\w*|derogatory|name[- ]calling|disrespect\w*|mean (to|remark|comment))\b",
    ),
]
_COMPILED = [(c, s, re.compile(p, re.IGNORECASE)) for c, s, p in _RULES]

# Words that are insults only when they describe a person ("you are stupid"), not a thing
# ("the food was useless").
_WEAK_INSULT = re.compile(
    r"\b(stupid\w*|idiot\w*|fool\w*|dumb|ugly|useless|worthless|lazy|loser|pathetic|nonsense|rubbish|trash)\b",
    re.IGNORECASE,
)
_PERSON = re.compile(
    r"\b(you|your|yourself|recipient|receiver|reader|child|person|someone|somebody|him|her|them"
    r"|he is|she is|they are|calls?|called|calling)\b",
    re.IGNORECASE,
)

# Grooming is often phrased with a "not" ("do not tell your parents"), so it is checked before
# clauses that deny harm are dropped.
_GROOMING = next(r for c, _, r in _COMPILED if c == "grooming")

# A clause that denies harm: "there is no threat", "it is not an insult", "without any insult".
_NEGATION = re.compile(
    r"\b(no|not|nothing|without|never|neither|nor|isn'?t|aren'?t|doesn'?t|does not|is not|are not|cannot|can'?t)\b",
    re.IGNORECASE,
)
_CLAUSES = re.compile(r"[.;:!?]|,\s*(?:but|and|so)?\s*|\bbut\b", re.IGNORECASE)

CONFIDENCE = 0.85
SAFE_CONFIDENCE = 0.8


def _harmful(category: str, severity: str) -> dict:
    return {"harmful": True, "category": category, "severity": severity, "confidence": CONFIDENCE}


def judge(meaning: str) -> dict:
    """A contract Verdict for the message Sunflower explained as [meaning]."""
    meaning = meaning or ""
    if _GROOMING.search(meaning):
        return _harmful("grooming", "high")
    for clause in _CLAUSES.split(meaning):
        if not clause or not clause.strip() or _NEGATION.search(clause):
            continue
        for category, severity, rule in _COMPILED:
            if rule.search(clause):
                return _harmful(category, severity)
        if _WEAK_INSULT.search(clause) and _PERSON.search(clause):
            return _harmful("insult", "medium")
    return {"harmful": False, "confidence": SAFE_CONFIDENCE}
