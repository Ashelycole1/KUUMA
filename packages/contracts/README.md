# kuuma-contracts

The only thing the builds share at runtime: the event envelope, 20 events, shared types,
API bodies (JSON Schema 2020-12) and one OpenAPI file per API prefix. Code for Kotlin, Dart,
Python and TypeScript is generated into `generated/` by `make gen`. Never edit `generated/`.

| Path | What |
| --- | --- |
| `schemas/events/envelope.json` | `eventId`, `type`, `version`, `occurredAt`, `deviceId`, `caseId?`, `payload` |
| `schemas/events/<type>.v<N>.json` | One file per event version; `x-kuuma` says publisher, where it travels, and whether it is phone only |
| `schemas/types/` | Severity, HarmCategory, GroomingStage, Language, AppSource, AgeBracket (placeholder), RecipientType, OpaqueRef, ... |
| `schemas/api/` | Request and response bodies used by `openapi/` |
| `openapi/` | `ai.yaml`, `companion.yaml`, `cases.yaml`, `verify.yaml`, `guardian.yaml` |
| `kotlin/main/` | Hand-written: `HarmClassifier`, `ReplyRiskChecker`, `ContractJson` |
| `kotlin/testFixtures/` | Kotlin contract-test harness (`ContractAssert`) |
| `codegen/` | Generator (Python stdlib) and per-language templates, including the harnesses |

## Contract-test harness

| Language | Use |
| --- | --- |
| Kotlin | `testImplementation(testFixtures(project(":contracts")))` → `ContractAssert.assertPublishes(Namespace.X, Events.Y, envelope)` |
| Python | `from kuuma_contracts.testing import assert_publishes, assert_consumes, validate_body` |
| Dart | `import 'package:kuuma_contracts/testing.dart';` → `assertPublishes('guardian', event.toJson())` |
| TypeScript | `import { assertPublishes, validateBody } from "@kuuma/contracts/testing"` |

The generator refuses: unnamed objects, inline enums, missing `additionalProperties: false`,
an event whose namespace is not its publisher, phone-only types inside cloud events, and
fields like `text` / `name` / `phone` in any event that is not phone only.
