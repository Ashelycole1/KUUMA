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

## What still needs to be done

### Blocked on a decision (team / product owner)
- [x] **Age brackets** agreed Nov 6: `age_12_under`, `age_13_15`, `age_16_18` (contract updated).
- [ ] **Thresholds per bracket:** `defaultThresholds()` in `flutter/lib/src/session.dart` are still placeholders.
- [ ] **Agreed delivery time for high-severity alerts.** Alerts are sent as soon as the phone is online;
      the heartbeat (`GuardianConfig.heartbeatMinutes`, 15) and the tamper window (`TAMPER_AFTER`, 60 min,
      mirrored as `silentAfter` in `children_screen.dart`) should be set from it.

### Blocked on setup
- [ ] **Push notifications (FCM).** Create the Firebase test project, put keys in `.env`, write a real
      `PushSender` in `service/kuuma_guardian_service/app.py` (it uses `RecordingPush` now), and register the
      parent app's FCM token with the service (needs a contract change: a token field or endpoint).
- [ ] **Full local stack.** Run `make dev` (Docker) and repeat the emulator run with every service, so the
      Casefile share upload also completes after approval (it currently fails with only Guardian running).

### Still to build
- [ ] **Second guardian** (brief: "a second guardian can be added"): invite flow, sharing the family key
      with a second parent phone, approvals from either.
- [ ] **Remove a child / cancel a pairing** that never completed (unpaired children stay in the list).
- [ ] **Deletion approvals:** Casefile has no deletion-request event yet; add it to contracts, then the
      approval kind is already in place (`ApprovalKind`).
- [ ] **Runtime permission prompt** for notifications on Android 13+ (the wizard opens app settings today).
- [ ] **Translations:** lg / nyn / sw for `guardian.*` strings (only a few sw keys exist); Sentinel and
      Companion have no translations for their permission reason keys (the wizard falls back to plain names).
- [ ] **Advice depth and child screens by bracket** (brief: the bracket decides these): agree with Companion
      how `guardian.policy.updated` should change what the child sees.
- [ ] **Mark alerts as read** on the parent's phone (the `read` field exists but is never set).

### Still to verify
- [ ] Android 5.0 (API 21) emulator, for both apps (`flutter_secure_storage`, `mobile_scanner`, `local_auth`).
- [ ] A low-end physical phone: real QR scan, battery use of the heartbeat, fingerprint unlock.
- [ ] Done criteria in the brief: a parent sets up a phone in one sitting without help (user test).
- [ ] CI on GitHub for this branch, then a PR into `main` (contract change → owner approval).

### Not Guardian's, but found here
- [ ] `:core-android:lintDebug` fails: "more than one jar in lintPublish" (Core).
