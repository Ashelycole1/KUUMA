# Guardian build — where it stands (2026-11-06)

## Done and verified

| Part | State | Check |
| --- | --- | --- |
| Service (`service/`) | Parents, pairing codes, pairing, signed policies, alerts, approvals, heartbeat, tamper sweep. | `uv run pytest modules/guardian/service` |
| Child-phone agent (`android/`) | `GuardianEngine`, `FamilyCrypto`, `GuardianModule`, `HttpGuardianApi`, `SqlGuardianStore`; wired into the child app's `KuumaRuntime` with the real API, device key and Core's permission registry. | `./gradlew :guardian:testDebugUnitTest :guardian:ktlintCheck` |
| Parent app (`flutter/`, `apps/parent-app`) | `ParentGate` (create account + PIN, then PIN/fingerprint; locks when the app is backgrounded, above the navigator so open screens are hidden too), Children (status, add child → QR, signed policy editor), Alerts (per-child filter, 7-day severity trend, guidance per category/kind, message decrypted on demand), Requests (signed approve/decline). | `flutter test` in `modules/guardian/flutter` (23) and `apps/parent-app` |
| Child setup wizard (`flutter/`) | Intro → consent → scan QR (`mobile_scanner`) → permissions from Core's registry → done; afterwards a read-only "Managed by your parent" view. Pull down to check in now. | widget tests with a mocked module channel |
| Fakes | `FakeGuardian.announceSampleChild()` / `announce_sample_child()` emit `guardian.pairing.completed` + `guardian.policy.updated`. The fake is off in debug builds (the real module handles shares); switch it on in the debug console to auto-approve. | fakes tests |

**Emulator run (2026-11-06, API 35, service on the laptop via uvicorn, no Docker):** parent account created →
child added → pairing code pasted into the child's wizard (debug builds can copy/paste the code because the
emulator has no camera) → child verified the parent-signed policy → signed heartbeats → `english-threat`
scenario raised an alert in under a second → parent decrypted the offending message → child's share request
approved on the parent's phone → decision delivered at the next check-in → Casefile continued the share.
A tamper alert also fired, correctly, while a required permission was off.

**Bugs found on the emulator and fixed:** `Unit` results crashed the module channel (child app bridge now
returns null); raw `enabled_*` settings reads throw `SecurityException` on new Android (now public APIs);
`POST_NOTIFICATIONS` was required but not in the manifest, so it could never be granted (manifest + test);
worker exceptions arrived as `ExecutionException` (now unwrapped); locking left pushed screens visible.

## Open

1. Push: real FCM sender behind `PushSender` (service uses `RecordingPush` until the Firebase test project exists).
2. Decisions needed: age brackets and thresholds (placeholders in `session.dart` `defaultThresholds`), the
   high-severity delivery time (heartbeat is 15 min; alerts are sent at once when online).
3. Not yet: second guardian, removing a child that never paired, deletion approvals (no Casefile event yet),
   lg / nyn / sw strings, tests on Android 5.0 and a low-end phone.
4. `:core-android:lintDebug` fails with "more than one jar in lintPublish" (pre-existing, Core).
