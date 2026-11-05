"""Model settings from the environment (.env). Nothing about the model is hard-coded elsewhere."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class AiConfig:
    mode: str = "stub"  # stub | sunflower
    sunflower_base_url: str = "http://localhost:11434/v1"
    sunflower_model: str = "sunflower-14b:q4"
    sunflower_timeout: float = 180.0
    queue_limit: int = 20
    cache_ttl_days: float = 7.0
    analyse_budget: float = 45.0  # below Sentinel's 60 s read timeout, queue wait included
    translate_budget: float = 180.0

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> AiConfig:
        e = os.environ if env is None else env
        d = cls()
        return cls(
            mode=e.get("KUUMA_AI_MODE", d.mode).strip().lower(),
            sunflower_base_url=e.get("KUUMA_SUNFLOWER_BASE_URL", d.sunflower_base_url),
            sunflower_model=e.get("KUUMA_SUNFLOWER_MODEL", d.sunflower_model),
            sunflower_timeout=float(e.get("KUUMA_SUNFLOWER_TIMEOUT", d.sunflower_timeout)),
            queue_limit=int(e.get("KUUMA_AI_QUEUE_LIMIT", d.queue_limit)),
            cache_ttl_days=float(e.get("KUUMA_AI_CACHE_TTL_DAYS", d.cache_ttl_days)),
            analyse_budget=float(e.get("KUUMA_AI_ANALYSE_BUDGET", d.analyse_budget)),
            translate_budget=float(e.get("KUUMA_AI_TRANSLATE_BUDGET", d.translate_budget)),
        )
