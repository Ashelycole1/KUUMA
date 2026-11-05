# Casefile — Evidence vault and cases

Owner: see `CODEOWNERS`. Skeleton from Core; everything here is yours to replace.

| | |
| --- | --- |
| Namespace | `ug.kuuma.casefile` |
| Folders | `android/`, `flutter/` (`kuuma_casefile`), `service/`, `web/` (verification viewer, TypeScript) |
| Cloud schema | `casefile` |
| Phone database | `vault.db` |
| API prefix | `/v1/cases`, `/v1/verify` |
| Topics you publish | `casefile.evidence.sealed`, `casefile.case.updated`, `casefile.case.escalated`, `casefile.share.requested`, `casefile.share.completed` |

## Start

```sh
cd modules/casefile
make test                                  # only this folder's tests
make fake SCENARIO=luganda-insults         # watch the event chain (child app > Debug)
```

- Android: `android/src/main/kotlin/ug/kuuma/casefile/CasefileModule.kt` registers with the child app and publishes a sample event. Replace the sample with real work.
- Your fake is in `fakes/android/.../Fakes.kt` (`FakeCasefile`) and `fakes/python/kuuma_fakes/fakes.py`. Keep it emitting what your real module emits (rule 6).
- Hash evidence with `Hashing.sha256Hex` (core-android). Case-ID tokens: `issue_case_token` / `case_auth()` in server-common.

Rules: [CONTRIBUTING.md](../../CONTRIBUTING.md). Contract changes go through `packages/contracts/`.
