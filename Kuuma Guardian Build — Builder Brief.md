# Kuuma Guardian Build — Builder Brief

Nov 5, 2026 · @Niwasiima Ashelycole

The Guardian owner builds the parent's side of Kuuma and the rulebook on the child's phone. That covers the parent app, setup and pairing, the age-bracket policy, alerts and approvals.

## Your job

**You own:** `modules/guardian/` (`android/`, `flutter/`, `service/`) and the Guardian screens in `apps/parent-app/`.

**You do not own:** detection (Sentinel), what the child sees (Companion), the evidence (Casefile), any AI (AI build), contracts, shared packages or the app shells themselves (Core).

**You start:** once the Core gate is passed. Your folder will already hold a running skeleton, with a placeholder screen in both apps and a service in the local stack.

**Done means:** a parent can set up a child's phone in one sitting, a high-severity flag reaches the parent's phone in an agreed time, and the child cannot change the policy.

## What you build

### Parent app (`flutter/` package `kuuma_guardian`, shown in `apps/parent-app/`)

- [ ] **Account:** tied to a random ID, with PIN or fingerprint login and device binding. One parent can manage several children, and a second guardian can be added.
- [ ] **Pairing:** shows a QR code that the child's phone scans; device keys are exchanged so alerts and approvals are signed end to end.
- [ ] **Policy:** the parent sets the child's age bracket and the apps to watch. The bracket decides alert thresholds, advice depth and which screens the child sees. Bracket values live in `kuuma-lang` (placeholders until the team agrees them).
- [ ] **Alerts dashboard:** alerts per child, a severity trend, and step-by-step guidance for each alert on how to talk to the child and what to avoid.
- [ ] **Approvals:** approve or decline Casefile's share and deletion requests for a child.
- [ ] **Tamper alerts:** tells the parent if Kuuma's permissions are turned off or the app is removed from the child's phone.

### Guardian agent on the child's phone (`android/` + `flutter/`)

- [ ] **Setup wizard:** the parent installs Kuuma, scans the pairing QR code and records consent. They grant the permissions every module declares in Core's permission registry (notification access, accessibility, keyboard) and choose the apps to watch.
- [ ] **Policy holder:** stores the current policy in `guardian.db` and publishes `guardian.policy.updated`; the child cannot change it.
- [ ] **Alert decisions:** on every flag, escalation or SOS, it checks the policy and sends an alert if it qualifies. High-severity flags and SOS always go through.
- [ ] **Heartbeat:** regular check-ins that report whether permissions are still granted.

Everything must run on Android 5.0 (API 21) and up, on both the child's and the parent's phone.

### Guardian service (`service/`)

- [ ] `POST /v1/guardian/pairings`, `PUT /v1/guardian/children/{childId}/policy`
- [ ] `POST /v1/guardian/alerts` and `GET /v1/guardian/alerts`: alerts sent as push notifications through Firebase Cloud Messaging. Each alert carries only the case ID, severity, category, platform, time and the offending message, never other chats or contacts.
- [ ] `POST /v1/guardian/approvals/{approvalId}` and `POST /v1/guardian/heartbeat`

## Interfaces

| Event or API | Direction | Other side | Where | Key fields |
| --- | --- | --- | --- | --- |
| `guardian.pairing.completed` | Publish | All modules | Cloud → phone | childId, deviceId, ageBracket |
| `guardian.policy.updated` | Publish | Sentinel, Companion, Casefile | Cloud → phone | childId, ageBracket, watchedApps, thresholds, policyVersion |
| `guardian.alert.sent` | Publish | Audit, metrics | Cloud | alertId, caseId, severity |
| `guardian.share.approved`, `guardian.share.declined` | Publish | Casefile | Cloud → phone | requestId, caseId, decidedAt |
| `sentinel.message.flagged`, `sentinel.flag.updated` | Consume | Sentinel | Phone | flagId, category, severity, appSource |
| `companion.sos.triggered` | Consume | Companion | Phone → cloud | caseId, triggeredAt |
| `casefile.case.updated`, `casefile.case.escalated` | Consume | Casefile | Phone → cloud | caseId, offenderLabel, topSeverity, reason |
| `casefile.share.requested`, `casefile.share.completed` | Consume | Casefile | Phone → cloud, cloud | requestId, caseId, recipientType |

## Your names

| Area | Value |
| --- | --- |
| Folders | `modules/guardian/android/`, `flutter/`, `service/`, plus Guardian screens in `apps/parent-app/` |
| Namespace | `ug.kuuma.guardian` |
| Flutter package | `kuuma_guardian` |
| Phone database | `guardian.db` (current policy, pairing keys) |
| Cloud schema | `guardian` (pairings, policies, alert summaries, approvals) |
| API prefix | `/v1/guardian` |
| Topics | `guardian.*` |
| Strings and flags prefix | `guardian_` |
| Branches | `guardian/<task>` |

## Working alone

- Build against Sentinel's fake flags, Companion's fake SOS and Casefile's fake cases and share requests.
- Everything runs locally for now; push notifications use a Firebase test project, with keys in the git-ignored `.env`.
- **Your fake:** emits `guardian.pairing.completed` and `guardian.policy.updated` for a sample child, and auto-approves share requests, for every other module to test with.

## Rules every builder follows

No cross-module imports; own only your folders, schema and database; publish only `guardian.*`; no model, prompt or LLM code; contract changes by pull request; keep your fake working; never log message content; short `guardian/<task>` branches with CI green and owner approval. Full text is in `CONTRIBUTING.md`.

## MVP and later

- **MVP:** parent app, setup wizard, pairing, age brackets, push alerts, approvals, tamper alerts.
- **Later:** second guardians such as a school counsellor, adult self-setup with chosen guardians, a guardian web dashboard.

## Done criteria

- [ ] A parent sets up a child's phone in one sitting, without help
- [ ] A high-severity flag reaches the parent's phone within the agreed time
- [ ] The child cannot change the policy or turn off Kuuma without the parent being told
- [ ] An alert never contains anything beyond its summary fields
- [ ] Works on an Android 5.0 emulator and a low-end physical phone

## Decisions you need

- [ ] The exact age brackets and alert thresholds
- [ ] The agreed alert delivery time for high-severity flags

**Owner skills:** Flutter, Kotlin, Python backend, push notifications.
