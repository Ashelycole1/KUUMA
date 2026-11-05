# Companion — The child's app and keyboard

Owner: see `CODEOWNERS`. Companion reacts to Sentinel's flags; it never reads other apps and never stores evidence.

| | |
| --- | --- |
| Namespace | `ug.kuuma.companion` |
| Folders | `android/`, `flutter/` (`kuuma_companion`), `service/` |
| Cloud schema | `companion` |
| Phone database | `companion.db` (not used yet) |
| API prefix | `/v1/companion` |
| Topics you publish | `companion.capture.requested`, `companion.sos.triggered`, `companion.feedback.submitted`, `companion.advice.shown` |

## What is where

| Part | Where |
| --- | --- |
| Module: flags → advice, keyboard context, the four published events | `android/.../CompanionModule.kt`, `Advice.kt` |
| Kuuma Keyboard (InputMethodService, layouts, suggestions, reply check) | `android/.../keyboard/` |
| Content pack reader | `android/.../content/ContentPack.kt` |
| Bridge for the Flutter screens | `android/.../CompanionBridge.kt` ↔ `flutter/lib/src/bridge.dart` |
| Help tab, advice cards, crisis screen, feedback, debug fake | `flutter/lib/src/` |
| Advice content (source) | `service/content/pack.yaml` |
| Pack builder (validate, sign, archive) and `GET /v1/companion/content-packs/latest` | `service/kuuma_companion_service/` |

## Content packs

All text the child reads comes from `service/content/pack.yaml`. After editing it:

```sh
uv run python -m kuuma_companion_service.packs build            # validates, signs, refreshes the copy bundled in the app
uv run python -m kuuma_companion_service.packs build --release  # also refuses unless every age bracket is reviewer-approved
```

Advice keys must exist in `packages/lang/data/advice-keys.yaml` (a test checks). Local builds sign with a
well-known DEBUG key; production sets `COMPANION_PACK_SIGNING_KEY` and refuses to start without it.

## Connecting the Flutter screens (Core: child app `MainActivity`)

The screens talk to Kotlin over `ug.kuuma.companion/bridge` (methods) and `ug.kuuma.companion/advice`
(events). `CompanionBridge` has no Flutter dependency; the host wires it up as shown in its KDoc. Until that
is done the screens run but show no flags, and the debug fake screen's buttons do nothing.

## Tests

```sh
uv run pytest modules/companion/service
./gradlew :companion:testDebugUnitTest :companion:ktlintCheck     # JDK 17
cd modules/companion/flutter && flutter test                      # Flutter 3.27.4
```

Rules: [CONTRIBUTING.md](../../CONTRIBUTING.md). Contract changes go through `packages/contracts/`.
