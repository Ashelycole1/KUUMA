# Companion — The child's app and keyboard

Owner: see `CODEOWNERS`. Skeleton from Core; everything here is yours to replace.

| | |
| --- | --- |
| Namespace | `ug.kuuma.companion` |
| Folders | `android/`, `flutter/` (`kuuma_companion`), `service/` |
| Cloud schema | `companion` |
| Phone database | `companion.db` |
| API prefix | `/v1/companion` |
| Topics you publish | `companion.capture.requested`, `companion.sos.triggered`, `companion.feedback.submitted`, `companion.advice.shown` |

## Start

```sh
cd modules/companion
make test                                  # only this folder's tests
make fake SCENARIO=luganda-insults         # watch the event chain (child app > Debug)
```

- Android: `android/src/main/kotlin/ug/kuuma/companion/CompanionModule.kt` registers with the child app and publishes a sample event. Replace the sample with real work.
- Your fake is in `fakes/android/.../Fakes.kt` (`FakeCompanion`) and `fakes/python/kuuma_fakes/fakes.py`. Keep it emitting what your real module emits (rule 6).
- Advice texts come from `packages/lang/data/advice-keys.yaml`; events carry only the key. The keyboard uses `ReplyRiskChecker`.

Rules: [CONTRIBUTING.md](../../CONTRIBUTING.md). Contract changes go through `packages/contracts/`.
