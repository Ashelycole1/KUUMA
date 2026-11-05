# Kuuma Casefile Build — Builder Brief

Nov 5, 2026 · @Niwasiima Ashelycole

The Casefile owner builds Kuuma's investigation core: the evidence vault, sealing and chain of custody, offender profiles, timelines, case files and anonymous sharing. Everything here must hold up in front of a school, police officer or court.

## Your job

**You own:** `modules/casefile/` (`android/`, `flutter/`, `service/`, `web/`).

**You do not own:** deciding what is harmful (Sentinel), translation and link suggestions (AI build — you call its interfaces), advice (Companion), approving shares for children or sending alerts (Guardian), contracts and shared packages (Core).

**You start:** once the Core gate is passed. Your folder will already hold a running skeleton, with a placeholder screen in the child app and a service in the local stack.

**Done means:** any tampering with a stored item is detected, an exported case file verifies on the verification page, and a Ugandan lawyer has reviewed the case file format.

## What you build

### Vault on the phone (`android/`)

- [ ] **Intake:** stores every flagged message (from `sentinel.message.flagged`) and every captured thread and screenshot (from `sentinel.capture.ready`) in `vault.db`. Media is kept as hash and metadata only. Suspected sexual images of minors are never copied; only the fact, hash and time are recorded.
- [ ] **Sealing:** SHA-256 hash and Keystore signature for every item, joined to an append-only hash chain. Hardware-backed keys are used on Android 6.0+, with Core's software fallback on Android 5.x.
- [ ] **Chain of custody:** a log of every view, export and share (who, when, which items).
- [ ] **Offender profiles:** messages grouped by sender under pseudonymous labels (Offender A, Offender B). Link suggestions come from `POST /v1/ai/link-suggestions`, and the adult victim, or the parent for a child, confirms each one.
- [ ] **Timeline and patterns:** one cross-app timeline per offender and per case, with escalation tracking (frequency, severity, late-night contact) and grooming stages from Sentinel's signals. It publishes `casefile.case.escalated` when a pattern crosses a threshold.
- [ ] **Retention and deletion:** export or permanently delete the vault; for a child, deletion needs the parent's approval.

### Incident screens (`flutter/`, package `kuuma_casefile`)

- [ ] Incident history, timeline, offender profiles and link confirmation, and case export, shown according to the age bracket

### Case service (`service/`)

- [ ] `POST /v1/cases/timestamps`: receives hash roots only and returns trusted timestamps (RFC 3161). Locally, use a mock timestamp authority; connect a real one before the pilot.
- [ ] `POST /v1/cases/{caseId}/bundles`: builds the case file as a PDF and a machine-readable bundle with a summary, timeline, exhibits, hashes and custody log. It adds English translations from `POST /v1/ai/translate`, marked machine-made, and the possible offence (for example, cyber harassment under section 24 of the Computer Misuse Act) labelled as guidance. Templates exist for school, police, platform and lawyer.
- [ ] `POST /v1/cases/{caseId}/shares`: an encrypted, expiring share link under the case ID. The victim's identity is added only if they choose, and for a child, every share waits for `guardian.share.approved`.

### Verification page (`web/`)

- [ ] `GET /v1/verify/{hash}` and a Next.js page where any recipient can check an exhibit against its hash and timestamp

## Interfaces

| Event or API | Direction | Other side | Where | Key fields |
| --- | --- | --- | --- | --- |
| `casefile.evidence.sealed` | Publish | Audit, metrics | Phone | itemId, flagId, hash, chainIndex |
| `casefile.case.updated` | Publish | Guardian | Phone → cloud | caseId, offenderLabel, incidentCount, topSeverity |
| `casefile.case.escalated` | Publish | Guardian, Companion | Phone → cloud | caseId, offenderLabel, reason, groomingStage |
| `casefile.share.requested` | Publish | Guardian | Phone → cloud | requestId, caseId, recipientType |
| `casefile.share.completed` | Publish | Guardian | Cloud | requestId, caseId, recipientType, expiresAt |
| `sentinel.message.flagged`, `sentinel.flag.updated` | Consume | Sentinel | Phone | flagId, severity, category, senderRef, groomingSignals |
| `sentinel.capture.ready` | Consume | Sentinel | **Phone only** | flagId, conversationRef, messages\[\], screenshotRef |
| `guardian.pairing.completed`, `guardian.policy.updated` | Consume | Guardian | Cloud → phone | childId, ageBracket |
| `guardian.share.approved`, `guardian.share.declined` | Consume | Guardian | Cloud → phone | requestId, caseId, decidedAt |
| `POST /v1/ai/translate`, `POST /v1/ai/link-suggestions` | Call | AI | Cloud | translation, suggested links |

## Your names

| Area | Value |
| --- | --- |
| Folders | `modules/casefile/android/`, `flutter/`, `service/`, `web/` |
| Namespace | `ug.kuuma.casefile` |
| Flutter package | `kuuma_casefile` |
| Phone database | `vault.db` (all evidence, profiles and cases; keys never leave the phone) |
| Cloud schema | `casefile` (case IDs, hash roots, timestamps, encrypted expiring bundles only) |
| API prefixes | `/v1/cases`, `/v1/verify` |
| Topics | `casefile.*` |
| Strings and flags prefix | `casefile_` |
| Branches | `casefile/<task>` |

## Working alone

- Build against Sentinel's fake flags and captures, Guardian's fake approvals (auto-approve), and Core's stub AI service.
- Everything runs locally for now, with MinIO standing in for cloud storage and a mock timestamp authority.
- **Your fake:** emits `casefile.case.updated`, `casefile.case.escalated` and `casefile.share.requested` from a sample case, for Guardian and Companion to test with.

## Rules every builder follows

No cross-module imports; own only your folders, schema and database; publish only `casefile.*`; no model, prompt or LLM code (call the AI interfaces); contract changes by pull request; keep your fake working; never log message content; short `casefile/<task>` branches with CI green and owner approval. Full text is in `CONTRIBUTING.md`.

## MVP and later

- **MVP:** vault, sealing, custody log, automatic offender grouping, basic PDF case file, anonymous share link.
- **Later:** cross-account link suggestions, grooming-stage tracking, recipient templates, verification page, investigator portal.

## Done criteria

- [ ] Editing or deleting any stored item is caught by the chain check
- [ ] An exported case file verifies on the verification page
- [ ] The cloud never holds message text or contact details, only hashes and encrypted bundles
- [ ] Works on an Android 5.0 emulator and a low-end physical phone
- [ ] A Ugandan lawyer has reviewed the case file format

**Owner skills:** security and cryptography, Kotlin, Python backend, PDF generation.
