"""Should a message go to Sunflower? Plain English skips it; anything else, or English with
Luganda / Swahili / Runyankole words mixed in, goes through.

PLACEHOLDER heuristic until the lexicon (packages/lang/lexicon) has non-English entries:
a short list of very common words in the three languages. Code-switched slang is
Sunflower's main job, so when in doubt the gate says yes.
"""

from __future__ import annotations

import re

_WORDS = re.compile(r"[a-z']+")
_MARKERS = {
    # Luganda
    "nze", "ggwe", "oli", "nnyo", "bwe", "ssomero", "enkya", "kukuba", "nja", "tujja", "ojja", "omusiru",
    "musiru", "kati", "wano", "ani", "kiki", "bambi", "webale", "sebo", "nnyabo", "naawe", "tukulaba",
    # Swahili
    "wewe", "mimi", "sana", "kesho", "nitakupiga", "mjinga", "nakupenda", "rafiki", "shule", "hapana",
    "ndiyo", "sasa", "kwa", "yako", "wako", "nini", "nyumbani",
    # Runyankole
    "niiwe", "nyowe", "munonga", "ninkukunda", "omwana", "eka", "orikukora", "ebi", "buhoro",
}  # fmt: skip


def needs_sunflower(text: str, language: str | None) -> bool:
    if language and language != "en":
        return True
    words = set(_WORDS.findall(text.lower()))
    return bool(words & _MARKERS)
