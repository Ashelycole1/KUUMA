# Nilinde Core Build — Main Builder Brief

Nov 5, 2026 · @Niwasiima Ashelycole

The main builder lays the base of the Nilinde repo so five other builders — AI, Sentinel, Companion, Casefile and Guardian — can start in parallel. Core is done when the handoff gate at the end of this brief is fully ticked.

## Your job

You build the ground everyone else stands on: a repo where any builder can clone, run one command and start coding against real interfaces with fake data.

Nilinde is a cyberbullying and harassment detection app for Android. It is split into six builds with one owner each:

| Build | Owner | Starts |
| --- | --- | --- |
| **0 · Core** | You, the main builder | Now |
| AI | AI owner | Data work now; code after your gate |
| 1 · Sentinel (detection, phone only) | Sentinel owner | After your gate |
| 2 · Companion (victim app and keyboard) | Companion owner | After your gate |
| 3 · Casefile (evidence and cases) | Casefile owner | After your gate |
| 4 · Guardian (parent app and alerts) | Guardian owner | After your gate |

**You own:** the repo, tooling and CI; `kuuma-contracts`, `kuuma-core-android`, `kuuma_ui`, `kuuma-lang` (except its lexicon) and `kuuma-server-common`; the API gateway; the child and parent app shells; the fake framework; the local stack and staging.

**You do not own:** any module feature, any model or prompt, or the AI packages. You create empty skeletons for them, and their owners fill them.

**Done means:** every box in the handoff gate is ticked. You then stay on Core: reviewing contract changes, maintaining shared packages, keeping CI green and leading weekly integration.

## Stage A — Repo and tooling

One monorepo, one pinned set of versions, one set of commands for every language.

- [ ] Create the repo with the folder tree below, plus `README.md`, `CONTRIBUTING.md`, `CODEOWNERS`, `.editorconfig` and `.pre-commit-config.yaml`
- [ ] Pin every version in `versions.toml`; use a Gradle version catalog (Kotlin), a melos workspace (Flutter), a uv workspace (Python 3.12) and pnpm (TypeScript)
- [ ] Add formatters and linters with pre-commit hooks: ktlint, `dart format` and `dart analyze`, ruff, ESLint and Prettier
- [ ] Add the `Makefile` with the standard commands
- [ ] Write the README quick-start: clone, `make bootstrap`, `make dev`, open your folder

```
kuuma/
├── README.md  CONTRIBUTING.md  CODEOWNERS  Makefile
├── versions.toml  .editorconfig  .pre-commit-config.yaml
├── packages/
│   ├── contracts/                 # Core
│   │   ├── schemas/events/        # envelope + 18 events
│   │   ├── schemas/types/
│   │   ├── openapi/               # one file per API prefix
│   │   └── generated/             # kotlin/ dart/ python/ ts/ - never hand-edited
│   ├── core-android/              # Core
│   ├── ui/                        # Core (kuuma_ui)
│   ├── lang/                      # Core
│   │   └── lexicon/               # AI owner fills
│   ├── server-common/             # Core
│   ├── kuuma-llm/                 # skeleton only; AI owner fills
│   └── ai-runtime-android/        # skeleton only; AI owner fills
├── ai/                            # skeleton only; AI owner fills
│   ├── service/  models/  training/  prompts/  eval/  datasets/
├── modules/                       # skeletons only; module owners fill
│   ├── sentinel/   android/
│   ├── companion/  android/ flutter/ service/
│   ├── casefile/   android/ flutter/ service/ web/
│   └── guardian/   android/ flutter/ service/
├── apps/
│   ├── child-app/                 # Core: shell with module registry
│   └── parent-app/                # Core: shell for Guardian screens
├── fakes/                         # Core: base fakes + scenarios
├── infra/
│   ├── docker-compose.yml  gateway/  deploy/
└── .github/workflows/
```

| Command | What it does |
| --- | --- |
| `make bootstrap` | Installs toolchains and dependencies for every language |
| `make dev` | Starts the local stack: PostgreSQL, Redis, MinIO, every service, the gateway |
| `make gen` | Regenerates contract types from the schemas |
| `make test` | Unit and contract tests for the current folder, or everything from the root |
| `make check` | Lint, format, boundary check, privacy scan |
| `make fake SCENARIO=<name>` | Plays a scripted chat scenario through the fakes |
| `make apk` | Builds the debug child and parent APKs |

## Stage B — Contracts

`kuuma-contracts` is the only thing the builds share at runtime. Everything below is written as JSON Schema and OpenAPI, and code is generated from it for Kotlin, Dart, Python and TypeScript.

- [ ] Write the envelope, the shared types and all 18 event schemas, starting at `v1`
- [ ] Write one OpenAPI file per API prefix, with every endpoint below returning sample data
- [ ] Set up `make gen` to generate all four languages into `generated/`
- [ ] Add the CI drift check and the contract-test harness (a helper each build uses to test that it publishes and consumes events that match the schemas)

### Envelope (every event)

`eventId` (UUID), `type`, `version`, `occurredAt` (UTC), `deviceId`, `caseId` (pseudonymous, random). No field may ever hold a real name, phone number or message text, except in events marked **phone only**.

### Shared types

| Type | Values |
| --- | --- |
| `Severity` | low, medium, high |
| `HarmCategory` | insult, harassment, threat, sexual\_harassment, sextortion, grooming, doxxing, hate, self\_harm\_encouragement |
| `GroomingStage` | trust\_building, gifts\_flattery, isolation, sexual\_talk, secrecy, meet\_or\_images |
| `Language` | en, lg (Luganda), nyn (Runyankole), sw (Swahili) |
| `AppSource` | whatsapp, sms, messenger, instagram, tiktok, x |
| `AgeBracket` | placeholder values until the team agrees the brackets |
| `RecipientType` | school, police, platform, lawyer |
| `OpaqueRef` | salted hash used for `messageRef`, `conversationRef` and `senderRef` |

### Events

| Event | Publisher | Where | Key fields |
| --- | --- | --- | --- |
| `sentinel.message.flagged` | Sentinel | Phone | flagId, messageRef, appSource, conversationRef, senderRef, category, severity, confidence, language |
| `sentinel.flag.updated` | Sentinel | Phone | flagId, severity, category, groomingSignals, translationRef |
| `sentinel.capture.ready` | Sentinel | **Phone only** | flagId, conversationRef, messages\[\], screenshotRef, capturedAt |
| `sentinel.conversation.active` | Sentinel | **Phone only** | conversationRef, appSource, hasFlags, topSeverity |
| `companion.capture.requested` | Companion | Phone | conversationRef, requestedAt |
| `companion.sos.triggered` | Companion | Phone → cloud | caseId, triggeredAt |
| `companion.feedback.submitted` | Companion | Phone | flagId, verdict, consentToTrain |
| `companion.advice.shown` | Companion | Phone → cloud | flagId, adviceKey |
| `casefile.evidence.sealed` | Casefile | Phone | itemId, flagId, hash, chainIndex |
| `casefile.case.updated` | Casefile | Phone → cloud | caseId, offenderLabel, incidentCount, topSeverity |
| `casefile.case.escalated` | Casefile | Phone → cloud | caseId, offenderLabel, reason, groomingStage |
| `casefile.share.requested` | Casefile | Phone → cloud | requestId, caseId, recipientType |
| `casefile.share.completed` | Casefile | Cloud | requestId, caseId, recipientType, expiresAt |
| `guardian.pairing.completed` | Guardian | Cloud → phone | childId, deviceId, ageBracket |
| `guardian.policy.updated` | Guardian | Cloud → phone | childId, ageBracket, watchedApps, thresholds, policyVersion |
| `guardian.alert.sent` | Guardian | Cloud | alertId, caseId, severity |
| `guardian.share.approved` | Guardian | Cloud → phone | requestId, caseId, decidedAt |
| `guardian.share.declined` | Guardian | Cloud → phone | requestId, caseId, decidedAt |

### API endpoints

| Prefix | Owner | Endpoints |
| --- | --- | --- |
| `/v1/ai` | AI | `POST analyse`, `POST advice`, `POST translate`, `POST link-suggestions`, `POST feedback`, `GET models/latest`, `GET lexicon/latest` |
| `/v1/companion` | Companion | `GET content-packs/latest` |
| `/v1/cases` | Casefile | `POST timestamps`, `POST {caseId}/bundles`, `POST {caseId}/shares` |
| `/v1/verify` | Casefile | `GET {hash}` |
| `/v1/guardian` | Guardian | `POST pairings`, `PUT children/{childId}/policy`, `POST alerts`, `GET alerts`, `POST approvals/{approvalId}`, `POST heartbeat` |

Sentinel has no cloud API; it is phone-only and calls `/v1/ai`.

### On-device AI interfaces

These are defined in `kuuma-contracts` as Kotlin interfaces, so modules can code against them before any model exists:

- `HarmClassifier.classify(text, context): Verdict` — category, severity, confidence
- `ReplyRiskChecker.check(draft, language): ReplyRisk` — risky spans, suggestion keys

## Stage C — Shared packages

These four packages carry the rules that keep builds from colliding, so the rules are enforced in code, not just written down. They must run on every phone from Android 5.0 (API 21) up, including low-memory and Android Go phones. Where a feature needs newer Android, such as hardware-backed Keystore keys (Android 6.0 and later), the package falls back to a software equivalent and records which one it used.

### `kuuma-core-android` (Kotlin library)

- [ ] **Event bus.** Typed publish and subscribe using the generated contract types. Each module registers its namespace, and the bus refuses to let it publish another module's topics. Phone-only events cannot be passed to any network client.
- [ ] **Encrypted storage.** A SQLCipher wrapper that gives each module its own database file (`sentinel.db`, `companion.db`, `vault.db`, `guardian.db`), with keys held in the Android Keystore.
- [ ] **Crypto helpers.** SHA-256 hashing, Keystore signing and verification, and salted `OpaqueRef` generation.
- [ ] **Redaction interface.** A `Redactor` that replaces names and phone numbers with placeholders such as `[CONTACT_1]`; Core ships a basic version and Sentinel extends it.
- [ ] **Permission registry.** Each module declares the Android permissions it needs; Guardian's setup wizard reads the registry.
- [ ] **Safe logger.** Logs only IDs, enums, numbers and timings; a lint rule rejects string arguments that are not constants.

### `kuuma_ui` (Flutter package)

- [ ] Theme and components (buttons, cards, severity badges, empty states)
- [ ] Translation setup for English, Luganda, Runyankole and Swahili, with a key-naming rule per module prefix
- [ ] PIN and fingerprint lock screen, a quick-hide helper and neutral-notification helpers for discreet mode
- [ ] Navigation-slot API: a module registers its screens, and the shell places them

### `kuuma-lang` (data package)

- [ ] YAML files for harm categories, severity scale, grooming stages, age brackets (placeholder), recipient types, languages and advice keys, matching the contract enums
- [ ] An empty `lexicon/` folder with its file format documented, for the AI owner to fill
- [ ] A loader for Kotlin, Dart and Python, plus a test that fails if YAML values and contract enums ever differ

### `kuuma-server-common` (Python library)

- [ ] FastAPI app factory with standard error format, request IDs and health and metrics endpoints
- [ ] Authentication: device-key signatures for phones and case-ID tokens; no endpoint accepts names or phone numbers
- [ ] Database base with one PostgreSQL schema per build and per-schema Alembic migrations
- [ ] Redis Streams client that enforces topic ownership, the same way the phone bus does
- [ ] Audit logging and the same content-free logging rule

## Stage D — Skeletons, shells, AI stubs, gateway and local stack

Every build's folder exists and runs before its owner touches it, already wired to the bus, the gateway and its own database schema.

### Module and AI skeletons

Each skeleton is a hello-world that compiles, registers itself and shows up in the apps:

- [ ] **Android library** that registers with the child-app module registry and publishes one sample event on its own topic
- [ ] **Flutter package** (where the module has screens) with one placeholder screen in its navigation slot
- [ ] **Service** (where the module has one) built on `kuuma-server-common`, answering its API prefix with sample data from the OpenAPI file, with its own schema and an empty first migration

| Build | Namespace | Flutter package | Cloud schema | Phone database | API prefix | Topics |
| --- | --- | --- | --- | --- | --- | --- |
| AI | `ug.kuuma.ai` | — | `ai` | — | `/v1/ai` | none |
| Sentinel | `ug.kuuma.sentinel` | — | none | `sentinel.db` | none | `sentinel.*` |
| Companion | `ug.kuuma.companion` | `kuuma_companion` | `companion` | `companion.db` | `/v1/companion` | `companion.*` |
| Casefile | `ug.kuuma.casefile` | `kuuma_casefile` | `casefile` | `vault.db` | `/v1/cases`, `/v1/verify` | `casefile.*` |
| Guardian | `ug.kuuma.guardian` | `kuuma_guardian` | `guardian` | `guardian.db` | `/v1/guardian` | `guardian.*` |

### AI stubs

These let modules build against AI from day one. The AI owner later swaps in real models behind the same interfaces.

- [ ] `ai-runtime-android` with `DummyHarmClassifier` (flags any message containing a word from a short test list as high severity) and `DummyReplyRiskChecker` (flags any draft containing a phone number)
- [ ] AI service stub that returns schema-valid sample responses for every `/v1/ai` endpoint
- [ ] `kuuma-llm` skeleton with the client and prompt-registry interfaces and no real model calls

### App shells

- [ ] **Child app** (Flutter + Android host): a module registry where each module registers its screens, permissions, event handlers and settings; bottom navigation built from those screens; a debug-only event console showing every bus event live
- [ ] **Parent app** (Flutter): login placeholder, navigation built from Guardian's registered screens
- [ ] Both apps build signed debug APKs with `make apk`

### Gateway and local stack

- [ ] API gateway routing each prefix to its service, with request IDs and rate limits
- [ ] `infra/docker-compose.yml` with PostgreSQL (schemas `ai`, `companion`, `casefile`, `guardian`), Redis, MinIO, the four services and the gateway
- [ ] Seed data: one test parent, one test child device and one sample case

## Stage E — Fakes, scenarios, CI and environments

Fakes let every builder work alone; CI stops anyone breaking someone else's work.

### Fake framework

- [ ] A base fake class for each module that emits its events with schema-valid sample data, switchable on or off per module in the child app's debug settings
- [ ] A scenario runner that plays a scripted chat as if it arrived through WhatsApp or SMS, driving the fakes in order
- [ ] Three scenarios to start: `luganda-insults` (repeated insults in Luganda over three days), `english-threat` (one threat of violence), `slow-grooming` (a grooming pattern over two weeks of messages)
- [ ] Scenarios contain only invented text, never real messages

### CI pipeline (every pull request)

1. Lint and format in every language
2. **Boundary check:** fails if a module imports another module, or model or prompt code appears outside the AI folders
3. **Codegen drift:** fails if generated contract code is out of date
4. Unit tests for changed folders
5. Contract tests for every publisher and consumer of a changed event
6. Build debug APKs and service Docker images
7. **Privacy scan:** fails if logs, analytics or test database dumps contain message-like text or phone numbers
8. Later, once a cloud host is chosen: deploy to staging on merge to `main`
9. Nightly: the `luganda-insults` scenario end to end on Android emulators at API 21 (oldest supported) and the latest version

### Environments

| Environment | Where | Data | AI |
| --- | --- | --- | --- |
| Local | Each laptop, Docker Compose | Fakes and scenarios | Stubs |
| Staging | Later: cloud, deployed from `main` | Test accounts and test phones only | Real models when ready, test keys, spending cap |
| Production | Later: cloud, released by tag | Real users after the pilot gate | Real models, monitored |

- [ ] No staging yet: everything runs locally for now. Secrets live in a git-ignored local .env file, never in the repo

## Handoff gate

When every box below is ticked on a fresh laptop, the other five builders start in their own folders.

- [ ] `git clone`, `make bootstrap` and `make dev` work with no manual steps
- [ ] Every module folder and the AI folder has a running hello-world skeleton
- [ ] The envelope, shared types, all 18 events and every API are merged, and generated types compile in Kotlin, Dart, Python and TypeScript
- [ ] `make fake SCENARIO=luganda-insults` shows events flowing in the child app's debug console
- [ ] The child app installs on a real Android phone and shows each module's placeholder screen
- [ ] The dummy AI runtime and the AI service stub return sample results in contract format
- [ ] CI runs every check on a pull request and blocks a deliberate boundary violation and a deliberate logged phone number
- [ ] CODEOWNERS names the owner of every folder
- [ ] A 30-minute walkthrough is held with all five builders, and the README answers their first-day questions

## Rules you set up and enforce

Write these into `CONTRIBUTING.md` and back each one with a CI check or a CODEOWNERS rule where possible.

1. **No cross-module imports.** A module imports shared packages, never another module. *(boundary check)*
2. **Own your data.** Each build reads and writes only its own schema and phone database. *(per-schema database users, per-module database files)*
3. **Publish only your own topics.** *(enforced by the bus and the Redis client)*
4. **AI lives only in AI folders.** No model files, prompts or LLM calls anywhere else. *(boundary check)*
5. **Contracts change by pull request.** Changes to `packages/contracts/` need your approval plus every build that publishes or consumes the changed event. Changes are additive; removing or renaming a field means a new event version. *(CODEOWNERS)*
6. **Ship a fake.** Every build keeps its fake working. *(nightly scenario)*
7. **Never log message content.** *(safe logger, lint rule, privacy scan)*
8. **Short branches.** Branch names are `<build>/<task>`, merged into `main` within days, with CI green and the folder owner's approval.
9. **Weekly integration.** You lead it: everyone merges, one more fake is swapped for the real part on the local stack and a shared test phone, and the team runs the end-to-end scenario together.

## Decisions needed

The first two block Stage A; the rest can be settled during Core, because placeholders keep the work moving.

- [ ] Who is the main builder, and who owns each of the other five builds (needed for CODEOWNERS)
- [ ] Git host and organisation (GitHub assumed)
- [ ] Target date for the handoff gate
- [ ] Cloud host for staging: decided, everything runs locally for now; choose AWS or Huawei Cloud and a budget before the pilot
- [ ] Gateway choice (for example Traefik or NGINX)
- [ ] The exact age brackets and thresholds (placeholders in `kuuma-lang` until agreed)
- [ ] Minimum Android version: decided, support as many phones as possible, including older ones. The floor is Android 5.0 (API 21), the oldest Flutter supports; features that need newer Android fall back gracefully
