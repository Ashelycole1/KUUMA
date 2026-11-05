"""The scrubbing guard: the last check before text reaches a model.

The phone's redactor already swaps names, numbers, emails and handles for [CONTACT_n],
[PHONE_n], [EMAIL_n] and [HANDLE_n]. This refuses anything that still looks like a phone
number or an email, so a redaction bug cannot leak contact details to the model.
It cannot spot names the phone did not know about; those rely on the phone's contact list.
"""

from __future__ import annotations

import re

#: [CONTACT_1], [PHONE_2], [EMAIL_1], [HANDLE_3]: the tokens the phone's Redactor writes.
PLACEHOLDER = re.compile(r"\[(?:CONTACT|PHONE|EMAIL|HANDLE)_\d{1,3}\]")

_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# Ugandan and international numbers: +256 7XX XXX XXX, 07XX XXXXXX, 256-7XX..., with spaces/dots/dashes.
_PHONE = re.compile(r"(?<![\w])\+?\d[\d\s().-]{7,}\d(?![\w])")


class UnsafeInputError(ValueError):
    """Text still holds something that looks like contact details. Never carries the text."""

    def __init__(self, kind: str):
        super().__init__(f"input still contains a {kind}")
        self.kind = kind


def check_safe(text: str) -> None:
    """Raises UnsafeInputError if `text` still looks like it holds a phone number or email."""
    stripped = PLACEHOLDER.sub(" ", text)
    if _EMAIL.search(stripped):
        raise UnsafeInputError("email")
    for m in _PHONE.finditer(stripped):
        if 9 <= sum(c.isdigit() for c in m.group()) <= 15:
            raise UnsafeInputError("phone number")


def placeholders(text: str) -> list[str]:
    return sorted(PLACEHOLDER.findall(text))


def check_placeholders(source: str, output: str, *, exact: bool) -> bool:
    """Output keeps the input's placeholders. exact: every one kept and none added (translations);
    otherwise none invented (interpretations may paraphrase one away)."""
    src, out = placeholders(source), placeholders(output)
    if exact:
        return src == out
    return set(out) <= set(src)
