# Guardian — Parent app, pairing and alerts

Owner: see `CODEOWNERS`. Skeleton from Core; everything here is yours to replace.

| | |
| --- | --- |
| Namespace | `ug.kuuma.guardian` |
| Folders | `android/`, `flutter/` (`kuuma_guardian`, also shown in the parent app), `service/` |
| Cloud schema | `guardian` |
| Phone database | `guardian.db` |
| API prefix | `/v1/guardian` |
| Topics you publish | `guardian.pairing.completed`, `guardian.policy.updated`, `guardian.alert.sent`, `guardian.share.approved`, `guardian.share.declined` |

## Start

```sh
cd modules/guardian
make test                                  # only this folder's tests
make fake SCENARIO=luganda-insults         # watch the event chain (child app > Debug)
```

- Android: `android/src/main/kotlin/ug/kuuma/guardian/GuardianModule.kt` registers with the child app and publishes a sample event. Replace the sample with real work.
- Your fake is in `fakes/android/.../Fakes.kt` (`FakeGuardian`) and `fakes/python/kuuma_fakes/fakes.py`. Keep it emitting what your real module emits (rule 6).
- The setup wizard reads every module's permissions from `ModuleRegistry.permissions`. Device signatures: `device_auth()` in server-common. Notifications must use `NeutralNotification` (kuuma_ui).

Rules: [CONTRIBUTING.md](../../CONTRIBUTING.md). Contract changes go through `packages/contracts/`.
