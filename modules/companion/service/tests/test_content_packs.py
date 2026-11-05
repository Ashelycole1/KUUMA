"""Content packs: the source is valid, builds are signed and reproducible, the API describes them."""

import base64
import copy
import io
import json
import zipfile

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi.testclient import TestClient
from kuuma_companion_service import packs
from kuuma_companion_service.app import app

from kuuma_contracts.testing import validate_body
from kuuma_lang import ids as lang_ids

client = TestClient(app)


@pytest.fixture(scope="module")
def src():
    return packs.load_source()


def test_every_advice_key_is_registered_in_kuuma_lang(src):
    registered = set(lang_ids("advice-keys"))
    used = {c["key"] for c in src["advice"]}
    assert used <= registered, f"add these to packages/lang/data/advice-keys.yaml first: {used - registered}"


def test_manifest_endpoint_matches_contract_and_build(src):
    r = client.get("/v1/companion/content-packs/latest")
    assert r.status_code == 200
    validate_body("types/artifact-manifest.json", r.json())
    built = packs.build(src)
    assert r.json()["sha256"] == built.sha256
    assert r.json()["sizeBytes"] == len(built.archive)
    assert r.json()["url"].endswith(f"/content-pack-{src['version']}.zip")
    assert r.headers["etag"] == f'"{built.sha256}"'


def test_build_is_reproducible(src):
    assert packs.build(src).archive == packs.build(copy.deepcopy(src)).archive


def test_archive_is_signed_with_the_pinned_key(src):
    built = packs.build(src)
    with zipfile.ZipFile(io.BytesIO(built.archive)) as z:
        body, sig = z.read("pack.json"), base64.b64decode(z.read("pack.sig"))
    pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(packs.public_key_b64()))
    pub.verify(sig, body)  # raises if invalid
    pack = json.loads(body)
    assert pack["format"] == packs.FORMAT and "review" not in pack


def test_bundled_copy_in_the_keyboard_is_current(src):
    assert packs.BUNDLED.read_text(encoding="utf-8") == packs.bundled_json(packs.build(src)), (
        "run: uv run python -m kuuma_companion_service.packs build"
    )


def test_release_build_refuses_unreviewed_content(src):
    assert not json.loads(packs.build(src).pack_json)["reviewed"]
    with pytest.raises(packs.PackError, match="not approved"):
        packs.build(src, release=True)
    approved = copy.deepcopy(src)
    for b in approved["review"]:
        approved["review"][b] = {"status": "approved"}
    assert json.loads(packs.build(approved, release=True).pack_json)["reviewed"]


def test_production_refuses_the_debug_key(monkeypatch):
    monkeypatch.delenv("COMPANION_PACK_SIGNING_KEY", raising=False)
    monkeypatch.setenv("KUUMA_ENV", "production")
    with pytest.raises(RuntimeError):
        packs.signing_key()


@pytest.mark.parametrize(
    "mutate,match",
    [
        (lambda s: s["byCategory"].pop("grooming"), "every harm category"),
        (lambda s: s["byCategory"]["insult"].append("advice.nope.missing"), "does not exist"),
        (lambda s: s["advice"][0]["text"].update({"fr": {"default": "x"}}), "unknown language"),
        (lambda s: s["advice"][0]["text"].update({"en": {"age_x": "x"}}), "unknown age bracket"),
        (lambda s: s["advice"][0].update({"text": {"lg": {"default": "x"}}}), "English default"),
        (lambda s: s["platforms"].pop("tiktok"), "every app source"),
        (lambda s: s.update({"crisisCategories": []}), "crisisCategories"),
        (lambda s: s.update({"helplines": []}), "helpline"),
        (lambda s: s.update({"version": "1.0"}), "MAJOR.MINOR.PATCH"),
    ],
)
def test_validation_rejects_broken_sources(src, mutate, match):
    bad = copy.deepcopy(src)
    mutate(bad)
    with pytest.raises(packs.PackError, match=match):
        packs.validate(bad)


def test_language_fallback_inputs(src):
    # Until native speakers add lg/nyn/sw, the pack honestly reports English only.
    assert packs.languages_with_text(src)[0] == "en"
