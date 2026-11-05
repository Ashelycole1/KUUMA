"""Smoke test against the real Sunflower in Ollama on this PC: prints answers and timings.

    uv run python ai/local/sunflower/smoke_test.py [--model sunflower-14b:q5]

Uses invented messages only. The timings set the budgets in AiConfig (analyse, translate).
"""

from __future__ import annotations

import argparse
import statistics
import sys
import time

from kuuma_llm.config import AiConfig
from kuuma_llm.ollama import OpenAICompatibleClient
from kuuma_llm.sunflower import SunflowerClient

TRANSLATE = [
    ("Webale nnyo.", "lg"),
    ("[CONTACT_1] oli musiru nnyo", "lg"),
    ("Wewe ni mjinga sana, [CONTACT_1].", "sw"),
    ("Ninkukunda munonga.", "nyn"),
]
INTERPRET = [
    ("Nja kukukuba bw'onodda ku ssomero enkya", ["[CONTACT_1]: tukulaba enkya"]),
    ("you think you're smart eh? musiru gwe, tomorrow tujja kukulaga", []),
    ("Nitakupiga kesho baada ya shule", []),
]


def main() -> int:
    cfg = AiConfig.from_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=cfg.sunflower_model)
    ap.add_argument("--base-url", default=cfg.sunflower_base_url)
    args = ap.parse_args()

    sf = SunflowerClient(OpenAICompatibleClient(args.base_url, args.model, timeout=cfg.sunflower_timeout))
    if not sf.health():
        print(f"Sunflower is not available at {args.base_url} (model {args.model}).")
        print("Start Ollama from the Start menu and check `ollama list`.")
        return 1

    print(f"Model {args.model} at {args.base_url}\nWarming up...")
    t = time.monotonic()
    sf.translate("Webale.", "en", "lg")
    print(f"  first answer (includes loading): {time.monotonic() - t:.1f} s\n")

    times: list[float] = []
    for text, lang in TRANSLATE:
        t = time.monotonic()
        r = sf.translate(text, "en", lang)
        times.append(time.monotonic() - t)
        print(f"translate {lang}->en  {times[-1]:5.1f} s  {text!r}\n    -> {r.text!r}")
    for text, ctx in INTERPRET:
        t = time.monotonic()
        r = sf.interpret(text, ctx)
        times.append(time.monotonic() - t)
        print(f"interpret          {times[-1]:5.1f} s  {text!r}\n    -> {r.english_meaning!r}")
    print(f"\nmedian {statistics.median(times):.1f} s, slowest {max(times):.1f} s over {len(times)} calls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
