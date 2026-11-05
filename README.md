# Kuuma

Cyberbullying and harassment detection for Android, built as six parts with one owner each:

| Build | Folder | What it is |
| --- | --- | --- |
| 0 · Core | `packages/`, `apps/`, `fakes/`, `infra/`, `tools/` | Contracts, shared packages, app shells, fakes, local stack, CI |
| AI | `ai/`, `packages/kuuma-llm/`, `packages/ai-runtime-android/` | Models, prompts, the `/v1/ai` service |
| 1 · Sentinel | `modules/sentinel/` | Detection on the phone (phone only, no cloud) |
| 2 · Companion | `modules/companion/` | The child's app screens and keyboard |
| 3 · Casefile | `modules/casefile/` | Evidence vault and cases |
| 4 · Guardian | `modules/guardian/` | Parent app, pairing and alerts |

## Quick start

```sh
git clone https://github.com/Ashelycole1/KUUMA.git kuuma && cd kuuma
make bootstrap      # installs every toolchain and dependency (first run: ~20 min)
make dev            # local stack: PostgreSQL, Redis, MinIO, all services, gateway on :8080
cd modules/<your-build> && make test
```

`make bootstrap` installs anything missing (uv + Python 3.12, JDK 17, Flutter 3.27.4,
the Android SDK, Node 22 + pnpm) into `~/.kuuma/toolchains`, at the versions pinned in
[`versions.toml`](versions.toml). It does not touch system-wide installs.
It cannot install **Docker**, which `make dev` needs: install Docker Desktop or Docker Engine yourself.

**Windows:** run everything from Git Bash. If `make` is missing: `winget install ezwinports.make`.

## Commands

| Command | What it does |
| --- | --- |
| `make bootstrap` | Installs toolchains and dependencies for every language |
| `make dev` | Starts the local stack (`make dev-down` stops it, `make dev-logs` tails it) |
| `make gen` | Regenerates contract types from the schemas |
| `make test` | Unit and contract tests for the current folder, or everything from the root |
| `make check` | Lint, format, boundary check, privacy scan, version pins, codegen drift |
| `make format` | Applies every formatter |
| `make fake SCENARIO=<name>` | Plays a scripted chat through the fakes (`luganda-insults`, `english-threat`, `slow-grooming`) |
| `make apk` | Builds the signed debug child and parent APKs into `out/apk/` |

## First-day questions

**Where do I work?** In your own folder only. See [`CODEOWNERS`](CODEOWNERS). Your folder
already has a running hello-world: an Android library registered with the child app, a Flutter
package with a placeholder screen (Companion, Casefile, Guardian), and a service answering your
API prefix with sample data (AI, Companion, Casefile, Guardian).

**How do I talk to other builds?** Only through `packages/contracts`: the 18 events on the event
bus and the five OpenAPI files. Never import another module. To change a contract, open a pull
request on `packages/contracts/` (see [CONTRIBUTING.md](CONTRIBUTING.md) rule 5), then run `make gen`.

**How do I publish an event on the phone?**

```kotlin
class GuardianModule : KuumaModule {
    override fun onRegister(context: ModuleContext) {
        context.events.subscribe(Events.CASEFILE_CASE_ESCALATED) { e -> /* react */ }
        context.publisher.publish(Events.GUARDIAN_ALERT_SENT, GuardianAlertSentPayload(...))
    }
}
```

The bus refuses topics that are not yours. Phone-only events (`sentinel.capture.ready`,
`sentinel.conversation.active`) do not compile against the network client (`CloudEventSender`).

**How do I publish in the cloud?** `app.state.bus.publish(event)` in your service
(`kuuma_server_common.StreamPublisher`), which writes to Redis stream `kuuma.events.<type>`.

**How do I work without the other builds?** Use the fakes. Every module has a base fake in
`fakes/` that emits schema-valid events and reacts to the others. In the child app's debug
build, open **Settings** to switch each fake on or off, and open **Debug** to watch every bus
event live. `make fake SCENARIO=luganda-insults` plays a three-day chat on a connected phone
(or prints it on the laptop if no phone is connected).

**How do I use AI before the models exist?** On the phone, `HarmClassifier` and
`ReplyRiskChecker` (in contracts) have dummy implementations in `ai-runtime-android`. In the
cloud, `/v1/ai/*` answers with contract-valid samples.

**Where is my database?** On the phone: `context.openDatabase()` opens your own SQLCipher
file (`sentinel.db`, `companion.db`, `vault.db`, `guardian.db`). In the cloud: your own
PostgreSQL schema, with migrations in `<your service>/migrations/`.

**How do I run the child app?** `make apk`, then `adb install -r out/apk/kuuma-child-debug.apk`,
or `cd apps/child-app && flutter run`. It runs on Android 5.0 (API 21) and later.

**What may I log?** IDs, enums, numbers and timings, and nothing else (rule 7). Use `SafeLog`
on Android and `get_logger()` in Python. Lint, tests and the CI privacy scan enforce this.

**What is still a placeholder?** Age brackets and thresholds (`packages/lang/data/age-brackets.yaml`),
code owners, parent login, PIN storage, Luganda/Runyankole translations, rate limits, and staging.

## Layout

```
packages/contracts/   schemas/ (envelope, 18 events, types, API bodies) · openapi/ · codegen/ · generated/ (never hand-edit)
packages/core-android event bus · encrypted storage · crypto · redactor · permissions · SafeLog (+ lint rule)
packages/ui           kuuma_ui: theme, components, translations, lock screen, discreet mode, navigation slots
packages/lang         shared vocabulary (YAML) + loaders for Kotlin, Dart, Python · lexicon/ (AI owner)
packages/server-common FastAPI factory, auth, DB per schema, Redis Streams, audit, content-free logs
apps/child-app        Flutter + Android host: module registry, bottom nav, debug event console
apps/parent-app       Flutter: login placeholder + Guardian's screens
fakes/                scenarios/ · android/ (phone fakes + player) · python/ (laptop runner)
infra/                docker-compose.yml · gateway/ (NGINX) · postgres/ · seed/ · keys/ (debug key) · deploy/ (later)
tools/                make targets, boundary check, privacy scan, version check
```

## Environments

| Environment | Where | Data | AI |
| --- | --- | --- | --- |
| Local | Each laptop (Docker Compose) | Fakes and scenarios | Stubs |
| Staging | Later: cloud, deployed from `main` | Test accounts and phones only | Real models when ready, test keys |
| Production | Later: cloud, released by tag | Real users after the pilot gate | Real models, monitored |

Secrets live in the git-ignored `.env`, which `make bootstrap` creates from `.env.example`. Never in the repo.
