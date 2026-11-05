# Sentinel — Detection, phone only

Owner: see `CODEOWNERS`. Skeleton from Core; everything here is yours to replace.

| | |
| --- | --- |
| Namespace | `ug.kuuma.sentinel` |
| Folders | `android/` (library `ug.kuuma.sentinel`) |
| Cloud schema | — |
| Phone database | `sentinel.db` |
| API prefix | none (calls `/v1/ai`) |
| Topics you publish | `sentinel.message.flagged`, `sentinel.flag.updated`, `sentinel.capture.ready` (phone only), `sentinel.conversation.active` (phone only) |

## Start

```sh
cd modules/sentinel
make test                                  # only this folder's tests
make fake SCENARIO=luganda-insults         # watch the event chain (child app > Debug)
```

- Android: `android/src/main/kotlin/ug/kuuma/sentinel/SentinelModule.kt` registers with the child app and publishes a sample event. Replace the sample with real work.
- Your fake is in `fakes/android/.../Fakes.kt` (`FakeSentinel`) and `fakes/python/kuuma_fakes/fakes.py`. Keep it emitting what your real module emits (rule 6).
- Use `HarmClassifier` (contracts) for verdicts and `BasicRedactor` (core-android) before anything goes to `/v1/ai`. Extend the redactor with contacts and app-specific rules.

Rules: [CONTRIBUTING.md](../../CONTRIBUTING.md). Contract changes go through `packages/contracts/`.
