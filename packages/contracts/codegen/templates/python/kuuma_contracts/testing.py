"""Contract-test harness.

Every build uses this to prove that what it publishes, consumes and serves matches
the schemas — not just its own idea of them:

    from kuuma_contracts.testing import assert_publishes, assert_consumes, validate_body

    def test_alert_sent_matches_contract():
        event = my_service.build_alert_event(...)
        assert_publishes("guardian", event)
"""

from __future__ import annotations

import json
from functools import cache, lru_cache
from importlib import resources
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

from kuuma_contracts.models import EVENTS, ContractModel

ID_BASE = "https://schemas.kuuma.ug/"


class ContractViolation(AssertionError):
    pass


@lru_cache(maxsize=1)
def bundle() -> dict[str, dict]:
    raw = resources.files("kuuma_contracts").joinpath("schema_bundle.json").read_text(encoding="utf-8")
    return json.loads(raw)


@lru_cache(maxsize=1)
def _registry() -> Registry:
    return Registry().with_resources((sid, Resource.from_contents(s)) for sid, s in bundle().items())


@cache
def _validator(schema_id: str) -> Draft202012Validator:
    if schema_id not in bundle():
        raise KeyError(f"no schema {schema_id}")
    return Draft202012Validator({"$ref": schema_id}, registry=_registry(), format_checker=FormatChecker())


def _as_dict(obj: Any) -> dict:
    if isinstance(obj, ContractModel):
        return obj.to_wire()
    if isinstance(obj, str | bytes):
        return json.loads(obj)
    return obj


def _check(schema_id: str, data: dict) -> None:
    errors = sorted(_validator(schema_id).iter_errors(data), key=lambda e: list(e.path))
    if errors:
        detail = "; ".join(f"{'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in errors[:10])
        raise ContractViolation(f"{schema_id} rejected the data: {detail}")


def event_schema_id(type_: str, version: int) -> str:
    return f"{ID_BASE}events/{type_}.v{version}.json"


def validate_event(event: Any) -> dict:
    """Validate one event (model, dict or JSON) against its schema. Returns it as a dict."""
    data = _as_dict(event)
    key = (data.get("type"), data.get("version"))
    if key not in EVENTS:
        raise ContractViolation(f"unknown event {key[0]}.v{key[1]}")
    _check(event_schema_id(*key), data)
    return data


def assert_publishes(namespace: str, event: Any, *, leaving_phone: bool = False) -> dict:
    """Assert `namespace` may publish this event, and that it matches the schema."""
    data = validate_event(event)
    spec = EVENTS[(data["type"], data["version"])]
    if spec.publisher != namespace:
        raise ContractViolation(f"{namespace} may not publish {spec.type}; it belongs to {spec.publisher}")
    if leaving_phone and spec.phone_only:
        raise ContractViolation(f"{spec.type} is phone only and must never leave the phone")
    return data


def assert_consumes(event: Any, expected_type: str, version: int = 1):
    """Assert an incoming event matches the schema and parse it into the generated model."""
    data = validate_event(event)
    if (data["type"], data["version"]) != (expected_type, version):
        raise ContractViolation(f"expected {expected_type}.v{version}, got {data['type']}.v{data['version']}")
    return EVENTS[(expected_type, version)].model.model_validate(data)


def validate_body(schema_file: str, body: Any) -> dict:
    """Validate an API body against a schema file, e.g. validate_body('api/alert-list.json', resp.json())."""
    data = _as_dict(body)
    _check(ID_BASE + schema_file, data)
    return data


def validate_schema_ref(schema: dict, data: Any) -> None:
    """Validate data against an arbitrary schema fragment whose $refs use bundle ids."""
    errors = list(Draft202012Validator(schema, registry=_registry()).iter_errors(data))
    if errors:
        raise ContractViolation("; ".join(e.message for e in errors[:10]))
