# Guardian build — where it stands (2026-11-05)

Branch: `guardian/continue` (off `main` after PR #3). Pick up from here.

## Done and verified

| Part | State | Check |
| --- | --- | --- |
| Service (`service/`) | Parents, pairing codes, pairing, signed policies, alerts, approvals, heartbeat, tamper sweep. `GET /approvals` now returns `childId`. | `uv run pytest modules/guardian/service packages/contracts/tests` → 53 pass; ruff clean |
| Child-phone agent (`android/`) | `GuardianEngine`, `FamilyCrypto`, `GuardianModule`, `HttpGuardianApi`, `SqlGuardianStore`. Flags that already carry a `caseId` now alert at once. | `./gradlew :guardian:testDebugUnitTest :guardian:ktlintCheck` (JDK 17) → 19 tests pass, ktlint clean |
| Contract | `api/approval.json` gains optional `childId` (additive). Codegen re-run. | contract tests pass |

**Bug fixed:** the signed decision message was `approvalId|requestId|decision|decidedAt`, but the server
stamps `decidedAt` *after* the parent signs, so no real decision could ever verify. It is now
`approvalId|requestId|decision` (Kotlin and Dart).

**Shared crypto test vectors** (key = bytes `(i*7+3) & 0xff`, i = 0..31), already asserted in the Kotlin test;
add the same asserts to the Dart test:
- key b64url `AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw`
- policy `age_placeholder_b|sms,whatsapp|0.500|medium|medium|1` → signature `HTf5k54Ky4WK7qoii-xrLoP0AsX-OyhchvkcHdP0p-Y`
- sealed `AQIDBAUGBwgJCgsMHkkPkEcmYMuc3ecpeDg1Z9Np9K2c4ZXLkL4QaXM_` opens to `Nkuba ku ssimu`

## Written but NOT yet compiled or tested (Flutter, `flutter/`)

- `pubspec.yaml`: added exact pins `crypto 3.0.6`, `pointycastle 3.9.1`, `http 1.2.2`, `qr_flutter 4.1.0`,
  `mobile_scanner 5.2.3`, `flutter_secure_storage 9.2.2`, plus `kuuma_lang`. `pub get` succeeded.
- `lib/src/family_crypto.dart`: HMAC sign/verify, AES-GCM seal/open, `PairingQr`, P-256 `DeviceKey`.
- `lib/src/api.dart`: `GuardianParentApi` + `HttpGuardianParentApi` (debug base URL `http://10.0.2.2:8080`).
- `lib/src/fake_api.dart`: in-memory `FakeGuardianParentApi` with helpers to add alerts/approvals/pair a child.
- `lib/src/session.dart`: `ParentSession` (account + PIN via PBKDF2, family keys in the vault, add child,
  update policy, open excerpts, sign decisions), `GuardianVault` (secure / memory), placeholder per-bracket thresholds.

## Next steps, in order

1. Run `flutter analyze` + fix compile errors in the four files above (Flutter 3.27.4 at `~/.kuuma/toolchains/flutter`).
2. Screens, registered in `lib/kuuma_guardian.dart` (replace `placeholder_screen.dart`):
   - `ParentGate` wrapping every parent tab: create account (set PIN) → `LockScreen` (kuuma_ui) → content.
   - Children tab: list with protection status (active / missing permissions / silent / waiting to pair),
     "Add a child" (nickname, age bracket from `KuumaLang.entries('age-brackets')`, apps, alert level) → QR screen (`qr_flutter`), policy editor.
   - Alerts tab: per-child filter, 7-day severity trend, detail with guidance (how to talk, what to avoid) per
     category and "show the message" (decrypt on demand only).
   - Approvals tab: approve / decline (signed).
   - Child settings `guardian.pairing`: setup wizard via `ModuleChannel('guardian')` →
     consent → scan QR (`mobile_scanner`, injectable for tests) → permissions (`permissions`/`openSettings`) → done;
     when paired, a read-only "managed by your parent" status.
3. Dart tests: crypto vectors, session flows against the fake API, widget tests for each screen.
4. Fake (rule 6): `fakes/android/.../Fakes.kt` `FakeGuardian` and `fakes/python/kuuma_fakes/fakes.py` should also
   emit `guardian.pairing.completed` + `guardian.policy.updated` for a sample child (brief, "Your fake").
5. Push: real FCM sender behind `PushSender` (service uses `RecordingPush` until the Firebase test project exists).
6. Core asks (not ours to edit): child app `KuumaRuntime` builds `GuardianModule()` with no config — it needs
   `GuardianConfig(androidContext, HttpGuardianApi(...), devicePublicKey, permissions = registry.permissions)`;
   parent app still shows Core's placeholder login (our `ParentGate` replaces it in practice).
7. Open the PR into `main` (contract change needs owner approval).

## Machine notes

Disk on this PC is nearly full (C: ~0.7 GB free); `flutter pub get` alone nearly filled it.
Toolchains/commands: see the Companion env memory — JDK 17 at `~/.kuuma/toolchains/jdk`,
`export JAVA_HOME=... ANDROID_HOME=$LOCALAPPDATA/Android/Sdk`.
