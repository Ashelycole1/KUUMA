"""Tests over the contract sources themselves: OpenAPI files, examples and codegen rules."""

import copy
import json
import sys
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONTRACTS / "codegen"))

import model as codegen_model  # noqa: E402

SCHEMAS = CONTRACTS / "schemas"
OPENAPI = CONTRACTS / "openapi"
ID_BASE = "https://schemas.kuuma.ug/"

EXPECTED_PREFIXES = {
    "ai.yaml": "/v1/ai",
    "companion.yaml": "/v1/companion",
    "cases.yaml": "/v1/cases",
    "verify.yaml": "/v1/verify",
    "guardian.yaml": "/v1/guardian",
}
EXPECTED_ENDPOINTS = {
    ("post", "/v1/ai/analyse"),
    ("post", "/v1/ai/advice"),
    ("post", "/v1/ai/translate"),
    ("post", "/v1/ai/link-suggestions"),
    ("post", "/v1/ai/feedback"),
    ("get", "/v1/ai/models/latest"),
    ("get", "/v1/ai/lexicon/latest"),
    ("get", "/v1/companion/content-packs/latest"),
    ("post", "/v1/cases/timestamps"),
    ("post", "/v1/cases/{caseId}/bundles"),
    ("post", "/v1/cases/{caseId}/shares"),
    ("get", "/v1/verify/{hash}"),
    ("get", "/v1/verify/shares/{requestId}"),
    ("post", "/v1/guardian/pairings"),
    ("put", "/v1/guardian/children/{childId}/policy"),
    ("post", "/v1/guardian/alerts"),
    ("get", "/v1/guardian/alerts"),
    ("post", "/v1/guardian/approvals/{approvalId}"),
    ("post", "/v1/guardian/heartbeat"),
    ("post", "/v1/guardian/parents"),
    ("post", "/v1/guardian/pairing-codes"),
    ("get", "/v1/guardian/children"),
    ("post", "/v1/guardian/approval-requests"),
    ("get", "/v1/guardian/approvals"),
}


def registry() -> Registry:
    resources = []
    for p in SCHEMAS.rglob("*.json"):
        doc = json.loads(p.read_text(encoding="utf-8"))
        resources.append((doc["$id"], Resource.from_contents(doc)))
    return Registry().with_resources(resources)


REG = registry()


def to_id(ref: str) -> str:
    # "../schemas/api/x.json" -> https://schemas.kuuma.ug/api/x.json
    return ID_BASE + ref.split("../schemas/", 1)[1]


def check(schema_ref: str, example) -> None:
    v = Draft202012Validator({"$ref": to_id(schema_ref)}, registry=REG)
    errors = [e.message for e in v.iter_errors(example)]
    assert not errors, f"{schema_ref}: {errors}"


def load_specs():
    return {name: yaml.safe_load((OPENAPI / name).read_text(encoding="utf-8")) for name in EXPECTED_PREFIXES}


def test_one_file_per_prefix_and_every_endpoint_present():
    assert {p.name for p in OPENAPI.glob("*.yaml")} == set(EXPECTED_PREFIXES)
    found = set()
    for name, spec in load_specs().items():
        for path, ops in spec["paths"].items():
            assert path.startswith(EXPECTED_PREFIXES[name] + "/"), f"{name}: {path} outside its prefix"
            for method in ops:
                if method in {"get", "post", "put", "delete", "patch"}:
                    found.add((method, path))
    assert found == EXPECTED_ENDPOINTS


def iter_examples():
    for name, spec in load_specs().items():
        for path, ops in spec["paths"].items():
            for method, op in ops.items():
                body = (op.get("requestBody") or {}).get("content", {}).get("application/json")
                if body:
                    yield f"{name} {method} {path} request", body["schema"]["$ref"], body.get("example")
                for status, resp in op["responses"].items():
                    if "$ref" in resp:
                        continue
                    media = resp.get("content", {}).get("application/json")
                    if media:
                        yield (
                            f"{name} {method} {path} {status}",
                            media["schema"]["$ref"],
                            media.get("example"),
                        )


@pytest.mark.parametrize("label,schema_ref,example", list(iter_examples()), ids=lambda x: str(x)[:60])
def test_every_example_matches_its_schema(label, schema_ref, example):
    assert example is not None, (
        f"{label}: every request and response needs an example (it is the stub's sample data)"
    )
    check(schema_ref, example)


def test_eighteen_events_and_all_shared_types():
    m = codegen_model.load()
    assert len(m.events) == 18
    for t in [
        "Severity",
        "HarmCategory",
        "GroomingStage",
        "Language",
        "AppSource",
        "AgeBracket",
        "RecipientType",
        "OpaqueRef",
    ]:
        assert t in m.types
    assert m.types["Language"].values == ["en", "lg", "nyn", "sw"]


def _with_tmp_schemas(tmp_path, mutate):
    root = tmp_path / "schemas"
    for p in SCHEMAS.rglob("*.json"):
        dst = root / p.relative_to(SCHEMAS)
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(p.read_text(encoding="utf-8"), encoding="utf-8")
    mutate(root)
    return root


def _edit(path: Path, fn):
    doc = json.loads(path.read_text(encoding="utf-8"))
    fn(doc)
    path.write_text(json.dumps(doc), encoding="utf-8")


def test_codegen_refuses_text_in_cloud_event(tmp_path):
    def mutate(root):
        _edit(
            root / "events/guardian.alert.sent.v1.json",
            lambda d: d["properties"]["payload"]["properties"].update({"text": {"type": "string"}}),
        )

    with pytest.raises(codegen_model.ContractError, match="personal data or message text"):
        codegen_model.load(_with_tmp_schemas(tmp_path, mutate))


def test_codegen_refuses_phone_only_type_in_cloud_event(tmp_path):
    def mutate(root):
        _edit(
            root / "events/casefile.case.updated.v1.json",
            lambda d: d["properties"]["payload"]["properties"].update(
                {"sample": {"$ref": "../types/captured-message.json"}}
            ),
        )

    with pytest.raises(codegen_model.ContractError, match="phone-only"):
        codegen_model.load(_with_tmp_schemas(tmp_path, mutate))


def test_codegen_refuses_foreign_namespace(tmp_path):
    def mutate(root):
        def fn(d):
            d["x-kuuma"] = copy.deepcopy(d["x-kuuma"]) | {"publisher": "guardian"}

        _edit(root / "events/sentinel.flag.updated.v1.json", fn)

    with pytest.raises(codegen_model.ContractError, match="namespace"):
        codegen_model.load(_with_tmp_schemas(tmp_path, mutate))
