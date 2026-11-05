"""Local seed data: one test parent, one test child device and one sample case.

Lives in infra/seed/seed.yaml so every service and fake agrees on the same IDs. The
test device's private key is in the seed too: it is a TEST key for the local stack only
and must never be used anywhere else.
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

SEED_FILE = Path(__file__).resolve().parents[3] / "infra" / "seed" / "seed.yaml"


@lru_cache(maxsize=1)
def seed() -> dict[str, Any]:
    path = Path(os.environ.get("KUUMA_SEED_FILE", SEED_FILE))
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def test_device_private_key() -> ec.EllipticCurvePrivateKey:
    pem = seed()["child_device"]["test_private_key_pem"].encode()
    key = serialization.load_pem_private_key(pem, password=None)
    assert isinstance(key, ec.EllipticCurvePrivateKey)
    return key
