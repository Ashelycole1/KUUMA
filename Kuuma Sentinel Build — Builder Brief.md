# Kuuma Sentinel Build — Builder Brief

Nov 5, 2026 · @Niwasiima Ashelycole

The Sentinel owner builds Kuuma's eyes and ears on the phone: reading incoming messages, capturing harmful conversations silently, and publishing a flag for every harmful message. Sentinel is phone-only; it has no cloud service of its own.

## Your job

**You own:** `modules/sentinel/` (`android/`).

**You do not own:** models or AI calls (AI build — you call its interfaces), storing evidence (Casefile), what the victim sees (Companion), alerts or settings (Guardian), contracts and shared packages (Core).

**You start:** once the Core gate is passed. Your folder will already hold a running skeleton registered in the child app.

**Done means:** on a real phone, a harmful WhatsApp or SMS message produces a correct `sentinel.message.flagged` event and a full thread capture, without the sender seeing anything.

## What you build

- [ ] **Notification reader.** A NotificationListenerService that reads incoming notifications from the apps the parent chose to watch (`watchedApps` in Guardian's policy): WhatsApp, SMS, Messenger, Instagram, TikTok, X. It removes duplicates, separates group chats from one-to-one chats, and recognises the sender. SMS is read from its notification, so no SMS permission is needed.
- [ ] **Normaliser.** Expands slang, emoji codes and misspellings using the lexicon in `kuuma-lang`.
- [ ] **Redactor.** Extends Core's `Redactor` to replace names and phone numbers with placeholders such as `[CONTACT_1]` before anything leaves the phone.
- [ ] **Screening.** Runs every message through `HarmClassifier.classify` (AI build, on-device). Messages that are uncertain or high-risk are sent, redacted, to `POST /v1/ai/analyse` for the final verdict.
- [ ] **Context scoring.** Local counters per contact (how often they talk, whether the tone is two-way) passed to the classifier as context, to tell banter from abuse.
- [ ] **Flag publishing.** Publishes `sentinel.message.flagged`, then `sentinel.flag.updated` if the cloud verdict changes it.
- [ ] **Automatic thread capture.** When a message is flagged, an AccessibilityService silently reads the full conversation on screen. It never opens the chat, replies or triggers read receipts, and publishes `sentinel.capture.ready` (phone only).
- [ ] **Backup capture.** Runs the same capture when Companion publishes `companion.capture.requested`.
- [ ] **Active conversation.** Publishes `sentinel.conversation.active` when the user opens a chat, so Companion's keyboard knows whether it has flags.
- [ ] **Feedback forwarding.** Passes `companion.feedback.submitted` to `POST /v1/ai/feedback` when the user consented, and lowers that contact's score.
- [ ] **Lexicon updates.** Downloads signed lexicon updates from `GET /v1/ai/lexicon/latest`, on Wi-Fi where possible.
- [ ] **Battery and data budget.** Batches cloud calls and backs off on low battery or data; on-device screening never stops.

Everything must run on Android 5.0 (API 21) and up, including low-memory phones. Where an API differs by Android version, handle both paths.

## Interfaces

| Event or API | Direction | Other side | Where | Key fields |
| --- | --- | --- | --- | --- |
| `sentinel.message.flagged` | Publish | Companion, Casefile, Guardian | Phone | flagId, messageRef, appSource, conversationRef, senderRef, category, severity, confidence, language |
| `sentinel.flag.updated` | Publish | Companion, Casefile, Guardian | Phone | flagId, severity, category, groomingSignals, translationRef |
| `sentinel.capture.ready` | Publish | Casefile | **Phone only** | flagId, conversationRef, messages\[\], screenshotRef, capturedAt |
| `sentinel.conversation.active` | Publish | Companion | **Phone only** | conversationRef, appSource, hasFlags, topSeverity |
| `companion.capture.requested` | Consume | Companion | Phone | conversationRef, requestedAt |
| `companion.feedback.submitted` | Consume | Companion | Phone | flagId, verdict, consentToTrain |
| `guardian.pairing.completed` | Consume | Guardian | Cloud → phone | childId, deviceId, ageBracket |
| `guardian.policy.updated` | Consume | Guardian | Cloud → phone | watchedApps, thresholds, policyVersion |
| `HarmClassifier.classify` | Call | AI | On-device | category, severity, confidence |
| `POST /v1/ai/analyse`, `POST /v1/ai/feedback`, `GET /v1/ai/lexicon/latest` | Call | AI | Cloud | verdict, acknowledgement, lexicon bundle |

## Your names

| Area | Value |
| --- | --- |
| Folder | `modules/sentinel/android/` |
| Namespace | `ug.kuuma.sentinel` |
| Phone database | `sentinel.db` (context counters and last verdicts only) |
| Cloud schema and API | none |
| Topics | `sentinel.*` |
| Strings and flags prefix | `sentinel_` |
| Branches | `sentinel/<task>` |

## Working alone

- Build against Core's `DummyHarmClassifier` and the stub AI service until the AI owner swaps in real ones; your code does not change when they do.
- Use Guardian's fake for policy and Companion's fake for capture requests and feedback.
- Test with Core's scenarios (`luganda-insults`, `english-threat`, `slow-grooming`) and on real phones with real WhatsApp and SMS.
- **Your fake:** keep a scripted stand-in that emits all four `sentinel.*` events from a sample chat, so Companion, Casefile and Guardian never need real messages.

## Rules every builder follows

No cross-module imports; own only your folder and database; publish only `sentinel.*`; no model, prompt or LLM code (call the AI interfaces); contract changes by pull request; keep your fake working; never log message content; short `sentinel/<task>` branches with CI green and owner approval. Full text is in `CONTRIBUTING.md`.

## MVP and later

- **MVP:** WhatsApp and SMS, English and Luganda, screening, flags, automatic and backup capture, active-conversation event.
- **Later:** Messenger, Instagram, TikTok and X; Runyankole and Swahili (comes from the AI build); voice-note capture.

## Done criteria

- [ ] Correct flags on all three Core scenarios and on a manual test set
- [ ] Capture never shows a read receipt or a typing indicator in tests on each supported app
- [ ] No message text or contact details in logs, and none sent to the cloud unredacted
- [ ] Works on an Android 5.0 emulator and a low-end physical phone

**Owner skills:** Android system services (notification listener, accessibility), Kotlin, testing on many devices.
