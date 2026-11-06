# Nilinde Companion Build — Builder Brief

Nov 5, 2026 · @Niwasiima Ashelycole

The Companion owner builds everything the victim sees and touches: the Nilinde Keyboard, advice, crisis support and discreet mode. Companion reacts to Sentinel's flags; it never reads other apps and never stores evidence.

## Your job

**You own:** `modules/companion/` (`android/`, `flutter/`, `service/`).

**You do not own:** detection (Sentinel), models and AI wording (AI build — you call its interfaces), the evidence vault and incident screens (Casefile), setup, policy and alerts (Guardian), contracts and shared packages (Core).

**You start:** once the Core gate is passed. Your folder will already hold a running skeleton with a placeholder screen in the child app.

**Done means:** a child can use the Nilinde Keyboard as their everyday keyboard, sees the right advice within a second of a flag, and can hide or lock Nilinde from any screen.

## What you build

### Nilinde Keyboard (`android/`, InputMethodService)

- [ ] **Full everyday keyboard** for English and Luganda first, then Runyankole and Swahili, with word suggestions in each
- [ ] **Context banner** above the keys when the open chat has flags, showing severity and a one-line tip (driven by `sentinel.conversation.active`)
- [ ] **Reply check** that runs the draft through `ReplyRiskChecker.check` (AI build, on-device), underlines risky parts and offers a safer wording
- [ ] **Safe replies:** one-tap calm responses, or a nudge not to reply, in the language being typed
- [ ] **Backup capture key:** a shield key that publishes `companion.capture.requested`
- [ ] **Quiet SOS:** a long-press that publishes `companion.sos.triggered` without leaving the chat

### Victim screens (`flutter/`, package `kuuma_companion`)

- [ ] **Advice cards** chosen by harm category, severity, age bracket and language from offline content packs. They include block-and-report steps for each platform and what not to delete. Optional personalised wording comes from `POST /v1/ai/advice`.
- [ ] **Crisis support:** for self-harm signals, calm screens and helplines such as Sauti 116, shown at once
- [ ] **Feedback:** a "this isn't abuse" button on every flag, publishing `companion.feedback.submitted`
- [ ] **Discreet mode:** neutral app name and icon (Android activity-alias), PIN or fingerprint lock and quick-hide from `kuuma_ui`, and neutral notifications hidden from the lock screen
- [ ] Advice tone and depth follow the age bracket in `guardian.policy.updated`

Companion never shows anything inside the abusive chat itself, so the offender sees nothing. Everything must run on Android 5.0 (API 21) and up; for example, use PIN unlock where a phone has no fingerprint sensor.

### Content service (`service/`)

- [ ] `GET /v1/companion/content-packs/latest`: signed, versioned advice content packs in four languages, downloaded in the background
- [ ] Content packs written with child-safety and counselling reviewers, per age bracket

## Interfaces

| Event or API | Direction | Other side | Where | Key fields |
| --- | --- | --- | --- | --- |
| `companion.capture.requested` | Publish | Sentinel | Phone | conversationRef, requestedAt |
| `companion.sos.triggered` | Publish | Guardian | Phone → cloud | caseId, triggeredAt |
| `companion.feedback.submitted` | Publish | Sentinel | Phone | flagId, verdict, consentToTrain |
| `companion.advice.shown` | Publish | Metrics | Phone → cloud | flagId, adviceKey |
| `sentinel.message.flagged` | Consume | Sentinel | Phone | flagId, category, severity, language, conversationRef |
| `sentinel.flag.updated` | Consume | Sentinel | Phone | flagId, severity, category |
| `sentinel.conversation.active` | Consume | Sentinel | **Phone only** | conversationRef, appSource, hasFlags, topSeverity |
| `casefile.case.escalated` | Consume | Casefile | Phone | caseId, reason, groomingStage |
| `guardian.pairing.completed`, `guardian.policy.updated` | Consume | Guardian | Cloud → phone | ageBracket, thresholds, policyVersion |
| `ReplyRiskChecker.check` | Call | AI | On-device | risky spans, suggestion keys |
| `POST /v1/ai/advice` | Call | AI | Cloud | advice text |

## Your names

| Area | Value |
| --- | --- |
| Folders | `modules/companion/android/`, `flutter/`, `service/` |
| Namespace | `ug.kuuma.companion` |
| Flutter package | `kuuma_companion` |
| Phone database | `companion.db` (content packs, advice history as IDs, discreet settings) |
| Cloud schema | `companion` |
| API prefix | `/v1/companion` |
| Topics | `companion.*` |
| Strings and flags prefix | `companion_` |
| Branches | `companion/<task>` |

## Working alone

- Build against Sentinel's fake flags, Guardian's fake policy, and Core's `DummyReplyRiskChecker` and stub AI service; your code does not change when the real ones arrive.
- Everything runs locally for now; the content service runs in the local stack.
- **Your fake:** a debug screen that publishes `companion.capture.requested`, `companion.sos.triggered` and `companion.feedback.submitted` on demand, for Sentinel and Guardian to test with.

## Rules every builder follows

No cross-module imports; own only your folders, schema and database; publish only `companion.*`; no model, prompt or LLM code (call the AI interfaces); contract changes by pull request; keep your fake working; never log message content or anything the child types; short `companion/<task>` branches with CI green and owner approval. Full text is in `CONTRIBUTING.md`.

## MVP and later

- **MVP:** keyboard in English and Luganda, context banner, reply check, safe replies, backup capture, SOS, advice cards, crisis screens, discreet mode.
- **Later:** Runyankole and Swahili keyboards, personalised advice, an iOS keyboard.

## Done criteria

- [ ] The keyboard is comfortable enough to use as an everyday keyboard on a low-end phone
- [ ] Advice appears within a second of a flag
- [ ] Quick-hide and lock work on every Nilinde screen
- [ ] Nothing the child types is stored or logged, except IDs of advice shown
- [ ] Child-safety and counselling reviewers have signed off on the content for each age bracket

**Owner skills:** Android keyboard development (InputMethodService), Flutter, UX writing, child-safety content.
