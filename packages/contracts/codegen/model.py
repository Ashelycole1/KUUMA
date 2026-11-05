"""Loads the contract schemas into a small, language-neutral model and validates the
house rules. Every emitter works from this model, so all four languages agree.

Schema rules the loader enforces (see packages/contracts/README.md):
  * every object type is named (its own file, or an event payload with a title);
  * properties are a $ref to a named type, a primitive, or an array of either;
  * events that are not phone-only carry no free-text, name or phone fields;
  * types marked x-kuuma.phoneOnly appear only inside phone-only events.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

SCHEMAS = Path(__file__).resolve().parent.parent / "schemas"
ID_BASE = "https://schemas.kuuma.ug/"

# Field names that would smell like personal data or message content.
FORBIDDEN_OUTSIDE_PHONE_ONLY = re.compile(
    r"^(text|body|message|messages|content|name|displayName|senderDisplayName|fullName|"
    r"firstName|lastName|phone|phoneNumber|msisdn|email|address)$"
)


class ContractError(Exception):
    pass


@dataclass(frozen=True)
class TRef:
    name: str


@dataclass(frozen=True)
class TPrim:
    kind: str  # string | integer | number | boolean


@dataclass(frozen=True)
class TList:
    item: TRef | TPrim


TypeExpr = TRef | TPrim | TList


@dataclass
class Field:
    name: str
    type: TypeExpr
    required: bool
    doc: str = ""


@dataclass
class EnumDef:
    name: str
    values: list[str]
    doc: str = ""
    kind: str = "enum"


@dataclass
class AliasDef:
    name: str
    pattern: str | None
    doc: str = ""
    kind: str = "alias"


@dataclass
class ObjectDef:
    name: str
    fields: list[Field]
    doc: str = ""
    phone_only: bool = False
    is_payload: bool = False
    kind: str = "object"


@dataclass
class EventDef:
    type: str
    version: int
    publisher: str
    where: str
    phone_only: bool
    title: str
    payload: str
    doc: str = ""

    @property
    def const_name(self) -> str:
        return re.sub(r"[.]", "_", self.type).upper()


@dataclass
class Model:
    types: dict[str, EnumDef | AliasDef | ObjectDef] = field(default_factory=dict)
    events: list[EventDef] = field(default_factory=list)
    bundle: dict[str, dict] = field(default_factory=dict)  # $id -> schema

    @property
    def namespaces(self) -> list[str]:
        return sorted({e.publisher for e in self.events})

    def sorted_types(self, kind: str):
        return [t for _, t in sorted(self.types.items()) if t.kind == kind]


def _file_to_title(path: Path) -> str:
    doc = json.loads(path.read_text(encoding="utf-8"))
    return doc["title"]


def load(root: Path = SCHEMAS) -> Model:
    model = Model()
    files = sorted(p for p in root.rglob("*.json"))
    titles: dict[Path, str] = {}
    for p in files:
        doc = json.loads(p.read_text(encoding="utf-8"))
        rel = p.relative_to(root).as_posix()
        expected_id = ID_BASE + rel
        if doc.get("$id") != expected_id:
            raise ContractError(f"{rel}: $id must be {expected_id}")
        model.bundle[expected_id] = doc
        titles[p.resolve()] = doc["title"]

    def resolve_ref(base: Path, ref: str) -> str:
        target = (base.parent / ref).resolve()
        if target not in titles:
            raise ContractError(f"{base.name}: $ref {ref} points at no schema")
        return titles[target]

    def type_expr(base: Path, owner: str, prop: str, s: dict) -> TypeExpr:
        if "$ref" in s:
            return TRef(resolve_ref(base, s["$ref"]))
        t = s.get("type")
        if t == "array":
            item = type_expr(base, owner, prop, s["items"])
            if isinstance(item, TList):
                raise ContractError(f"{owner}.{prop}: nested arrays are not allowed")
            return TList(item)
        if t in ("string", "integer", "number", "boolean"):
            if "enum" in s:
                raise ContractError(f"{owner}.{prop}: inline enums are not allowed; make a named type")
            return TPrim(t)
        raise ContractError(f"{owner}.{prop}: properties must be a $ref, a primitive or an array (got {s})")

    def object_def(
        base: Path, name: str, s: dict, *, doc: str, phone_only=False, is_payload=False
    ) -> ObjectDef:
        if s.get("additionalProperties") is not False:
            raise ContractError(f"{name}: objects must set additionalProperties: false")
        required = set(s.get("required", []))
        unknown = required - set(s["properties"])
        if unknown:
            raise ContractError(f"{name}: required names unknown properties {sorted(unknown)}")
        fields = [
            Field(pname, type_expr(base, name, pname, ps), pname in required, ps.get("description", ""))
            for pname, ps in s["properties"].items()
        ]
        return ObjectDef(name, fields, doc, phone_only, is_payload)

    for p in files:
        rel = p.relative_to(root).as_posix()
        doc = json.loads(p.read_text(encoding="utf-8"))
        title = doc["title"]
        desc = doc.get("description", "")
        if rel == "events/envelope.json":
            continue
        if rel.startswith("events/"):
            meta = doc.get("x-kuuma") or {}
            for key in ("event", "version", "publisher", "where", "phoneOnly"):
                if key not in meta:
                    raise ContractError(f"{rel}: x-kuuma.{key} missing")
            etype = meta["event"]
            if not rel.endswith(f"{etype}.v{meta['version']}.json"):
                raise ContractError(f"{rel}: file name must be {etype}.v{meta['version']}.json")
            if etype.split(".")[0] != meta["publisher"]:
                raise ContractError(f"{rel}: an event's namespace must be its publisher")
            if doc["properties"]["type"].get("const") != etype:
                raise ContractError(f"{rel}: properties.type.const must be {etype}")
            payload = doc["properties"]["payload"]
            pname = payload.get("title")
            if pname != title + "Payload":
                raise ContractError(f"{rel}: payload title must be {title}Payload")
            if pname in model.types:
                raise ContractError(f"duplicate type name {pname}")
            model.types[pname] = object_def(
                p, pname, payload, doc=desc, phone_only=meta["phoneOnly"], is_payload=True
            )
            model.events.append(
                EventDef(
                    etype,
                    meta["version"],
                    meta["publisher"],
                    meta["where"],
                    meta["phoneOnly"],
                    title,
                    pname,
                    desc,
                )
            )
            continue
        if title in model.types:
            raise ContractError(f"duplicate type name {title}")
        if "enum" in doc:
            model.types[title] = EnumDef(title, list(doc["enum"]), desc)
        elif doc.get("type") == "string":
            model.types[title] = AliasDef(title, doc.get("pattern"), desc)
        elif doc.get("type") == "object":
            phone_only = bool((doc.get("x-kuuma") or {}).get("phoneOnly"))
            model.types[title] = object_def(p, title, doc, doc=desc, phone_only=phone_only)
        else:
            raise ContractError(f"{rel}: a type must be an enum, a string alias or an object")

    model.events.sort(key=lambda e: (e.type, e.version))
    _check_privacy(model)
    return model


def _refs(expr: TypeExpr):
    if isinstance(expr, TRef):
        yield expr.name
    elif isinstance(expr, TList):
        yield from _refs(expr.item)


def _reachable(model: Model, name: str, seen=None) -> set[str]:
    seen = seen if seen is not None else set()
    if name in seen:
        return seen
    seen.add(name)
    t = model.types[name]
    if isinstance(t, ObjectDef):
        for f in t.fields:
            for r in _refs(f.type):
                _reachable(model, r, seen)
    return seen


def _check_privacy(model: Model) -> None:
    phone_only_types = {
        n for n, t in model.types.items() if isinstance(t, ObjectDef) and t.phone_only and not t.is_payload
    }
    allowed_users: set[str] = set()
    for ev in model.events:
        reach = _reachable(model, ev.payload)
        if ev.phone_only:
            allowed_users |= reach
            continue
        leaked = reach & phone_only_types
        if leaked:
            raise ContractError(f"{ev.type}: is not phone-only but carries phone-only types {sorted(leaked)}")
        for tname in reach:
            t = model.types[tname]
            if isinstance(t, ObjectDef):
                for f in t.fields:
                    if FORBIDDEN_OUTSIDE_PHONE_ONLY.match(f.name):
                        raise ContractError(
                            f"{ev.type}: field {tname}.{f.name} looks like personal data or message text; "
                            "only phone-only events may carry it"
                        )
    # Phone-only types must not be reachable from API bodies either.
    for name, t in model.types.items():
        if isinstance(t, ObjectDef) and not t.phone_only and name not in allowed_users:
            leaked = (_reachable(model, name) - {name}) & phone_only_types
            if leaked:
                raise ContractError(f"{name}: uses phone-only types {sorted(leaked)}")
