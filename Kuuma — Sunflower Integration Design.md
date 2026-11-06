# Nilinde — Sunflower Integration Design

Nov 6, 2026 · @Niwasiima Ashelycole · revised the same day to match the contracts and the code

This document is how Sunbird AI's Sunflower model is wired into Nilinde's code. Sunflower runs locally on SURFACE-02 through Ollama, and only the AI build talks to it: modules call the AI service, the AI service calls `kuuma-llm`, and `kuuma-llm` calls Sunflower.

## At a glance

&#91;embedded content: request path · 3 callers, 3 layers, 1 fallback\]

Every request goes through the AI service and `kuuma-llm`, which redact-check, format and validate it. If Sunflower is slow or down, nothing breaks: Sentinel keeps its on-device verdict and Casefile retries.

## Scope and goals

Sunflower is Nilinde's language specialist: it turns Luganda, Runyankole, Swahili and mixed-language messages into clear English meaning, and produces translations. It never decides on its own whether a message is harmful.

**Sunflower does:**

- **Interpret:** explain in plain English what a redacted, possibly code-switched or slang-heavy message means, including hidden insults or threats
- **Translate:** produce English translations of exhibits for case files, always labelled machine-made
- **Localise advice (later):** reword Companion's advice cards into the victim's language

**Sunflower does not:**

- Decide category or severity. The on-device classifier does the first screen; the reasoning model (Gemma 4, coming next) gives the final cloud verdict, using Sunflower's interpretation as input.
- See phone numbers, emails or images. Names are removed only when the phone knows them (contacts, the sender's display name); see Safety.
- Run on the phone. It runs on SURFACE-02 for now, and later on a GPU server.

**Goals for the first integration:**

1. `POST /v1/ai/translate` and the interpretation step of `POST /v1/ai/analyse` use real Sunflower output
2. No module code changes when the stub AI service is swapped for the real one, beyond the additive contract fields below
3. Every call is safe by construction: redacted input, one-line message text, validated output, no content in logs
4. Works on SURFACE-02 alone, with no internet connection or paid API

## Components and where the code lives

Four layers, each with one job. Only the bottom two know Sunflower exists.

| Layer | Folder | Job | Status |
| --- | --- | --- | --- |
| **Callers** | `modules/sentinel`, `modules/casefile`, `modules/companion` | Send redacted text to the AI service over HTTP; know nothing about models | Sentinel sends `onDevice` and ignores echoed verdicts; Casefile does not call translate yet |
| **AI service** | `ai/service/` (FastAPI) | Owns the `/v1/ai` endpoints; one model slot with priorities; validates responses against `kuuma-contracts` | `translate` and `analyse` (phase 1) built behind `KUUMA_AI_MODE`; the rest are stubs |
| **`kuuma-llm`** | `packages/kuuma-llm/` | Model client, prompt registry, scrubbing guard, output checks, cache, config | Built and unit-tested |
| **Model runtime** | Ollama on SURFACE-02; setup in `ai/local/sunflower/`, weights in `%USERPROFILE%\kuuma-models\sunflower` | Serves `sunflower-14b:q4` on an OpenAI-compatible API | Setup script and Modelfile done |

The code as built:

```
packages/kuuma-llm/kuuma_llm/
  __init__.py       # LlmClient / LlmRequest interfaces (Core)
  scrub.py          # scrubbing guard + placeholder checks
  ollama.py         # OpenAICompatibleClient for Ollama: complete, health, <think> stripping
  sunflower.py      # SunflowerClient: translate, interpret, health; tag mapping, output checks
  prompts.py        # FilePromptRegistry: ai/prompts/<id>/<version>.prompt.yaml
  cache.py          # result cache keyed by a hash of prompt + version + model + input
  language_gate.py  # should this message go to Sunflower?
  config.py         # AiConfig from KUUMA_* environment variables
ai/service/kuuma_ai_service/
  app.py            # /v1/ai/translate, /v1/ai/analyse, /healthz/model; stubs for the rest
  gate.py           # ModelGate: one slot, analyse before translate, queue limit, budgets
ai/prompts/translate/1.prompt.yaml, ai/prompts/interpret/1.prompt.yaml
ai/local/sunflower/  Modelfile.template, setup-sunflower.ps1, smoke_test.py, README.md
```

Later: `reasoning.py` (Gemma 4 client, same interface style) and a router that picks the model per task.

The AI service imports only `kuuma_llm`. It never builds HTTP calls to Ollama itself, so swapping Sunflower-14B for Sunflower-Qwen3.5-9B, or moving it to a GPU server, is a configuration change.

## Installation on SURFACE-02

Setting up Sunflower takes about 20–40 minutes, mostly the 9.0 GB download. Do it once on SURFACE-02; other laptops keep `KUUMA_AI_MODE=stub`.

### Prerequisites

| Need | Check |
| --- | --- |
| Windows 10/11, 64-bit, 32 GB RAM, NVIDIA RTX 3060 (the Laptop GPU has 6 GB) | Settings → System → About |
| At least 20 GB free disk | File Explorer → This PC |
| Latest NVIDIA driver | `nvidia-smi` in PowerShell shows the GPU |
| winget | `winget --version` |
| The repo's toolchain: `uv` (Python 3.12 comes from the repo pin) | `uv --version` |

No Hugging Face account or token is needed: `Sunbird/Sunflower-14B-GGUF` is public.

Before starting: plug in the Surface and set Windows power mode to **Best performance** (Settings → System → Power).

### Step 1 — Install, download and build (one script)

From the repo root in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File ai\local\sunflower\setup-sunflower.ps1
```

The script installs Ollama with winget if needed, sets `OLLAMA_KEEP_ALIVE=30m`, downloads `sunflower-14B-q4_k_m.gguf` (resumes if stopped) into `%USERPROFILE%\kuuma-models\sunflower`, writes a Modelfile from `Modelfile.template` (Sunbird's system message, chat template and stop tokens), builds the Ollama model `sunflower-14b:q4`, and asks it a test question.

For higher quality (about 10.5 GB, slower), run it again with `-Quant q5_k_m`. That creates `sunflower-14b:q5` alongside the first.

Model weights never go in the repo: `*.gguf` is git-ignored and the boundary check refuses model files.

### Step 2 — Check the model

```powershell
ollama list                     # sunflower-14b:q4 is listed
ollama run sunflower-14b:q4 "Translate from Luganda to English: Webale nnyo."
ollama ps                       # how much of the model is on the GPU
```

With 6 GB of VRAM, a 14B Q4 model does not fit on the GPU: Ollama splits it between GPU and CPU. The first answer can take a minute while the model loads.

### Step 3 — Run the tests and the smoke test

```powershell
uv sync
uv run pytest packages\kuuma-llm ai\service      # no model needed
uv run python ai\local\sunflower\smoke_test.py    # real model; prints answers and timings
```

Record the smoke test's timings. They set the budgets in the Reliability section.

### Step 4 — Configure Nilinde

In `.env` at the repo root (git-ignored; `.env.example` lists the variables):

```
KUUMA_AI_MODE=sunflower
KUUMA_SUNFLOWER_BASE_URL=http://localhost:11434/v1
KUUMA_SUNFLOWER_MODEL=sunflower-14b:q4
KUUMA_SUNFLOWER_TIMEOUT=180
```

Run the AI service alone with `uv run uvicorn kuuma_ai_service.app:app --port 8081`, or with the whole stack through `make dev`; in Docker the service reaches Ollama on the host at `http://host.docker.internal:11434/v1` (`KUUMA_SUNFLOWER_DOCKER_URL`). `GET /healthz/model` reports `sunflower: up | loading | down`.

### Sharing the Surface with the team: not by default

The Ollama API has no password. `OLLAMA_HOST=0.0.0.0` would let anyone on the network send it prompts and read what it answers about children's messages. Teammates use `KUUMA_AI_MODE=stub`. If sharing becomes necessary, put a small token-checking proxy in front of Ollama and allow only the teammates' addresses in Windows Firewall.

### Troubleshooting

| Problem | Fix |
| --- | --- |
| `ollama` not found | Open a new PowerShell window after installing; or run `%LOCALAPPDATA%\Programs\Ollama\ollama.exe` |
| Download stopped part-way | Run the script again; it resumes |
| Script blocked by execution policy | Use the `-ExecutionPolicy Bypass` command exactly as shown |
| Very slow answers, `ollama ps` shows 100% CPU | Update the NVIDIA driver, plug in the charger, set Best performance, restart Ollama |
| Out of memory | Close other heavy apps; stay on Q4\_K\_M; don't load two large models at once |
| Smoke test says Sunflower is not available | Start Ollama from the Start menu (or run `ollama serve`), then check `ollama list` shows `sunflower-14b:q4` |
| Requests hang for minutes, GPU at 100% | Something else is using the GPU. The Android emulator with `-gpu auto` took about 2 GB of VRAM and stalled the model; stop it or start it with `-gpu swiftshader_indirect`. Then restart Ollama, which also clears a stuck request |

### Uninstall or reset

```powershell
ollama rm sunflower-14b:q4
Remove-Item -Recurse $env:USERPROFILE\kuuma-models\sunflower
```

## Use cases and request flows

Three flows use Sunflower. Analyse is the most important and the most time-sensitive.

### 1. Analyse (Sentinel → `POST /v1/ai/analyse`)

Triggered when the on-device classifier is uncertain or scores a message as high risk.

1. Sentinel redacts the message and sends it with the detected language, up to 10 redacted context messages and its own verdict (`onDevice`).
2. The AI service checks it twice: the shared server guard refuses personal data (`422 personal_data_rejected`), then the scrubbing guard refuses anything that still looks like a phone number or email (`422 unsafe_input`). Sentinel keeps its on-device verdict.
3. **Language gate:** messages not labelled English go to Sunflower. Messages labelled English go too if they contain common Luganda, Swahili or Runyankole words (a placeholder word list until the lexicon has entries), because code-switched slang is exactly what Sunflower is for.
4. `SunflowerClient.interpret()` returns the English meaning, using the last 5 context messages.
5. **Phase 1 (now):** the service returns the phone's own verdict with `verdictSource: "on_device"` and the interpretation attached. Sentinel ignores verdicts it sent itself, so nothing changes on the phone yet. If Sunflower fails or the budget runs out, the same answer comes back without an interpretation.
6. **Phase 2:** the reasoning model judges category, severity and grooming signals from the text plus the interpretation; `verdictSource` becomes `"reasoning_model"` and Sentinel publishes `sentinel.flag.updated` if the verdict changed.

Without `onDevice` there is nothing to return in phase 1, so the service answers `503`.

### 2. Translate (Casefile → `POST /v1/ai/translate`)

Triggered when Casefile builds a case file and an exhibit is not in English. **Casefile does not call this yet**: that is part of step 4.

1. Casefile sends redacted exhibit text, its language (`from`) and the target (`to`, normally `en`).
2. Guards → cache lookup → `SunflowerClient.translate()` at temperature 0 → placeholder check → cache store.
3. The response carries `machineTranslated: true`, the model and the prompt version, which Casefile prints beside each translation. The original text stays the evidence.

Not time-sensitive: Casefile queues these and builds the file when they finish.

### 3. Localise advice (Companion → content-pack build, later)

Companion's advice cards are written in English and reviewed by humans. Sunflower can produce draft Luganda, Runyankole and Swahili versions **offline, at content-pack build time**, which native speakers then review before the pack ships. Live, unreviewed advice wording is not in this phase, because a mistranslated safety tip to a child is a real risk.

## Interfaces

Modules see only the HTTP endpoints (`packages/contracts/openapi/ai.yaml`); the AI service sees only `SunflowerClient`. Every field below is in `kuuma-contracts`; the fields added for Sunflower are optional, so nothing that works today breaks.

### `SunflowerClient` (Python, in `kuuma-llm`)

| Method | Input | Returns | Raises |
| --- | --- | --- | --- |
| `health()` | — | `bool`: server up and model listed | never |
| `translate(text, target, source=None)` | redacted text; codes `en`, `lg`, `nyn`, `sw` | `TranslationResult(text, source_language, target_language, model, prompt_version, latency_ms, machine_translated=True)` | `UnsafeInputError`, `SunflowerError`, `ModelUnavailableError`, `ModelTimeoutError`, `ValueError` |
| `interpret(text, context=None)` | redacted text; earlier redacted messages | `InterpretationResult(english_meaning, model, prompt_version, latency_ms)` | `UnsafeInputError`, `SunflowerError`, `ModelUnavailableError`, `ModelTimeoutError` |

### `POST /v1/ai/translate`

```json
// request (contract TranslateRequest)
{ "text": "[CONTACT_1] oli musiru nnyo", "from": "lg", "to": "en" }

// 200 response (TranslateResponse; machineTranslated, model, promptVersion, latencyMs are new and optional)
{
  "text": "[CONTACT_1] you are very stupid",
  "from": "lg", "to": "en",
  "machineTranslated": true,
  "model": "sunflower-14b:q4",
  "promptVersion": "translate@1",
  "latencyMs": 6400
}
```

### `POST /v1/ai/analyse`

```json
// request (AnalyseRequest; onDevice is new and optional)
{
  "text": "Nja kukukuba bw'onodda ku ssomero enkya",
  "language": "lg",
  "appSource": "whatsapp",
  "context": ["[CONTACT_1]: tukulaba enkya"],
  "onDevice": { "harmful": true, "category": "threat", "severity": "medium", "confidence": 0.58 }
}

// 200 response, phase 1 (AnalyseResponse; verdictSource, interpretation, latencyMs are new and optional)
{
  "verdict": { "harmful": true, "category": "threat", "severity": "medium", "confidence": 0.58 },
  "groomingSignals": [],
  "modelVersion": "sunflower-14b:q4",
  "verdictSource": "on_device",
  "interpretation": {
    "englishMeaning": "I will beat you when you come to school tomorrow.",
    "model": "sunflower-14b:q4", "promptVersion": "interpret@1"
  },
  "latencyMs": 7100
}
```

In phase 2, `verdictSource` becomes `"reasoning_model"` and `verdict` comes from Gemma 4.

### Error responses

All errors use the shared format `{"error": {"code", "message", "requestId"}}`; messages never quote the input.

| Status | Code | When | Caller does |
| --- | --- | --- | --- |
| `422` | `personal_data_rejected` | The shared server guard found personal data | Fix redaction; keep the on-device verdict |
| `422` | `unsafe_input` | The scrubbing guard found a phone number or email | Same |
| `422` | `invalid_body` | Body does not match the contract (including an unsupported language code) | Fix the request |
| `503` | `model_busy` | More than `KUUMA_AI_QUEUE_LIMIT` analyses are already waiting | Keep the on-device verdict; Casefile retries later |
| `503` | `model_unavailable` | Ollama down, model not loaded, unusable answer, or analyse without `onDevice` | Same |
| `504` | `model_timeout` | The request's budget ran out (waiting included) | Same |

For analyse, model failures do not become errors: the service answers `200` with the on-device verdict and no interpretation.

## Prompts

Every prompt lives in one registry with an ID and a version, so any output can be traced back to exactly the prompt that produced it.

### Rules for Sunflower prompts

Measured on SURFACE-02 on Nov 6: Sunflower is a translation model first. Given a long instruction block with fenced text, it **translated the instructions** instead of following them. Given Sunbird's short phrasing, it answered correctly. So the prompts are deliberately short.

- **Translation is Sunbird's phrasing and nothing else:** `Translate from {Source} to {Target}: {text}` or `Translate to {Target}: {text}`, with the **English language name** (Luganda, Runyankole, Swahili), never the ISO code.
- **Interpretation is one short instruction** followed by `Message: {text}`, with earlier messages, if any, listed above it.
- **Temperature 0** for translation; **0.2** for interpretation. Small `max_tokens` (200 and 120): at about 2 tokens a second, long answers are slow.
- **Keep Sunbird's system message** from the Modelfile. Task instructions go in the user turn.
- **One line per message:** each message is flattened to a single line, so it cannot add lines that look like more of the prompt. No fence markers: Sunflower would translate them too.
- **Placeholders in Sunflower's own tags:** the phone writes `[CONTACT_n]`, `[PHONE_n]`, `[EMAIL_n]`, `[HANDLE_n]`, but Sunflower rewrites them to the tags it was trained on (`[NAME_n]`, `[NUMBER_n]`). Nilinde sends `[NAME_n]` and `[NUMBER_n]` and maps them back; the placeholder check then runs on Nilinde's tags.

### Registry

One folder per prompt id and one file per version, `ai/prompts/<id>/<version>.prompt.yaml`, loaded by `kuuma_llm/prompts.py`:

```yaml
# ai/prompts/translate/1.prompt.yaml
id: translate
version: 1
model_family: sunflower
temperature: 0.0
max_tokens: 200
template: "{instruction}: {text}"
```

| Prompt ID | Used by | Version |
| --- | --- | --- |
| `translate` | `/v1/ai/translate` | 1 |
| `interpret` | `/v1/ai/analyse` | 1 |
| `localise_advice` | Content-pack build script | Later |

**Changing a prompt:** add a new version (never edit an old one in place), run the evaluation set against both, attach the report to the pull request, then switch the default. Old versions stay so past case files can still say which prompt produced their translations.

## Safety

Safety is enforced in code at every layer, so a mistake in one layer is caught by the next.

| Risk | Control | Where |
| --- | --- | --- |
| Contact details reach the model | The phone's Redactor writes `[CONTACT_n]`, `[PHONE_n]`, `[EMAIL_n]`, `[HANDLE_n]`; the shared server guard and the scrubbing guard both refuse anything that still looks like a phone number or email | Phone, `kuuma_server_common`, `kuuma_llm/scrub.py` |
| A name the phone did not know | **Not caught by any guard.** Names are only removed if they are in the contact list or are the sender's name | Known limit; review before the pilot |
| A message tries to instruct the model ("ignore your rules…") | Sunflower translates such a message rather than obeying it (tested on SURFACE-02); messages are flattened to one line; every answer goes through the output checks | `sunflower.py`, prompt templates |
| The model invents placeholders or drops them | Tags are mapped to Sunflower's own and back; translations must then keep every placeholder exactly; interpretations may not add any | `sunflower.py`, `scrub.check_placeholders` |
| A broken or harmful answer reaches a module | Empty, oversized or placeholder-breaking answers are refused; every response is validated against the contract; anything invalid becomes a `503`, never a guess | `kuuma-llm`, AI service |
| Translation mistaken for the original | `machineTranslated: true` on every translation; the original text stays the evidence | Contract + Casefile |
| Message text leaks into logs | The service logger refuses free text; logs carry event, model, prompt id and version, latency and status only; errors never quote input | `kuuma_server_common.safe_log`, AI service |
| Cached outputs are message content | Keys are hashes and hold no text; values stay in memory with a 7-day expiry. A shared cache (Redis) must be private and expire too | `kuuma_llm/cache.py` |
| The Ollama API is open to the network | Not shared by default (see Installation) | SURFACE-02 setup |
| Images or sexual images of minors sent to a model | The API accepts text only; no image fields exist | Contract |
| The model decides harm alone | Sunflower only interprets; the verdict comes from the classifier or reasoning model | Service orchestration |

Tests make this concrete: the scrub suite refuses real-looking Ugandan numbers and emails, and the AI service tests check that sample message text never appears in its logs.

## Reliability on SURFACE-02

Sunflower-14B on one Surface handles one request at a time and takes seconds per answer, so the design queues work, caches results and always has a fallback. Nilinde never waits on Sunflower to protect a child: the on-device verdict and alerts work without it.

### Concurrency and queueing

- **One request at a time to Sunflower** (`ModelGate`). Extra requests wait.
- **Two priorities:** `analyse` (from Sentinel) runs before `translate` (from Casefile).
- **Queue limit:** if `KUUMA_AI_QUEUE_LIMIT` (20) analyses are already waiting, new ones get `503 model_busy` at once.
- If a caller's budget runs out, it gets its answer (timeout or fallback) at once, but the slot stays taken until the model really finishes, so Ollama never gets two requests at the same time.

### Budgets and retries

| Call | Budget (waiting + running) | Retries |
| --- | --- | --- |
| `analyse` (interpret step) | 45 s (`KUUMA_AI_ANALYSE_BUDGET`) — below Sentinel's 60 s read timeout, so the phone always gets an answer | 0 — the on-device verdict already stands |
| `translate` | 180 s (`KUUMA_AI_TRANSLATE_BUDGET`) | None in the service; Casefile retries later |
| `health` | 5 s | — |

**Measured on SURFACE-02 (Nov 6, Q4\_K\_M, 57% CPU / 43% GPU, nothing else using the GPU):** translations 1.6–5.6 s, interpretations 6–16 s, median 5.6 s, slowest 15.9 s; about 2.2 tokens a second while writing; the first load takes 20–25 s. Through the AI service over HTTP: translate 2.8 s, analyse 3.3 s. The 45 s analyse budget leaves room for two or three queued requests.

### Caching

- Key: SHA-256 of prompt id + version + model + languages or context + input text. The key holds no message text.
- In memory in the AI service for now (Redis later), for 7 days (`KUUMA_AI_CACHE_TTL_DAYS`), at most 5000 entries.
- Identical exhibits in a case file, and repeated forwarded messages, are translated once.

### Keeping the model warm

- `OLLAMA_KEEP_ALIVE=30m` (set by the setup script) keeps Sunflower loaded between requests during a working session.
- On startup the AI service sends one tiny translation through the gate so the first real message is not slow.
- `GET /healthz/model` reports `sunflower: up | loading | down`, the model, and whether the slot is busy.

### Fallbacks

| Failure | What happens |
| --- | --- |
| Sunflower down, busy or over budget | `analyse` returns the on-device verdict with no interpretation; `translate` returns `503`/`504` and Casefile retries |
| Ollama not running | Same as above; health reports `down` |
| Output fails a check | Treated as a failure, never returned |

## Configuration and environments

All settings come from environment variables in the git-ignored `.env` (listed in `.env.example`); nothing about the model is hard-coded in the AI service.

| Variable | Default | Meaning |
| --- | --- | --- |
| `KUUMA_AI_MODE` | `stub` | `stub` (sample responses) or `sunflower` (real model for translate and analyse) |
| `KUUMA_SUNFLOWER_BASE_URL` | `http://localhost:11434/v1` | Ollama's OpenAI-compatible API |
| `KUUMA_SUNFLOWER_DOCKER_URL` | `http://host.docker.internal:11434/v1` | The same, as seen from the AI container in `make dev` |
| `KUUMA_SUNFLOWER_MODEL` | `sunflower-14b:q4` | Ollama model tag (`sunflower-14b:q5` for higher quality) |
| `KUUMA_SUNFLOWER_TIMEOUT` | `180` | HTTP timeout to Ollama, in seconds |
| `KUUMA_AI_ANALYSE_BUDGET` | `45` | Seconds an analyse may wait and run |
| `KUUMA_AI_TRANSLATE_BUDGET` | `180` | Seconds a translation may wait and run |
| `KUUMA_AI_QUEUE_LIMIT` | `20` | Max waiting analyses |
| `KUUMA_AI_CACHE_TTL_DAYS` | `7` | Cache lifetime |
| `OLLAMA_KEEP_ALIVE` | `30m` | Set on SURFACE-02, read by Ollama, not by Nilinde |

| Where | `KUUMA_AI_MODE` | Sunflower runs on |
| --- | --- | --- |
| Any laptop without the model | `stub` | Nothing; the stub answers |
| SURFACE-02 | `sunflower` | `localhost` |
| CI | `stub` | Nothing; unit tests use a fake model |
| Pilot (later) | `sunflower` | A GPU server, chosen before the pilot |

`KUUMA_AI_MODE=stub` is the default everywhere, so the five other builders never need the model to work.

## Testing and evaluation

Three levels: unit tests prove the code is safe, the smoke test proves the model runs, and the evaluation set proves Sunflower is good enough for Nilinde's messages.

| Level | What | Runs where | Status |
| --- | --- | --- | --- |
| Unit | `packages/kuuma-llm/tests`: guard, placeholders and tag mapping, prompts, one-line messages, `<think>` stripping, cache, errors, health, language gate (mocked HTTP) | CI and any laptop | Passing |
| Service | `ai/service/tests`: endpoints in sunflower mode with a fake model, fallbacks, queue order and limits, budgets, no text in logs; stub mode answers contract-valid samples | CI and any laptop | Passing |
| Smoke | `ai/local/sunflower/smoke_test.py`: real translations and interpretations with timings | SURFACE-02 | Passing (Nov 6) |
| Evaluation | Labelled Nilinde test set, scored per language | SURFACE-02 | Waits for the dataset |

### Evaluation set for Sunflower

Built by the AI owner with native speakers, using invented or consented messages only:

- 50+ messages per language (Luganda, Runyankole, Swahili, mixed with English), covering insults, threats, sexual harassment, grooming, banter and neutral chat
- For each: a reference English meaning and translation written by a native speaker

Measured per language, for each candidate (Sunflower-14B Q4, Q5, and later Sunflower-Qwen3.5-9B):

- **Translation quality:** chrF against the reference, the same metric Sunbird reports
- **Meaning kept:** whether a reviewer judges that the interpretation captures the insult or threat (yes/no)
- **Placeholder integrity:** share of outputs that keep every placeholder exactly
- **Latency:** median and slowest response on SURFACE-02

A model or prompt change ships only if it beats the current one on meaning kept, with no drop in placeholder integrity.

## Implementation plan

The AI owner works through these in order; each step is one or two small pull requests on `ai/<task>` branches.

**Done**

- [x] Contract change (additive): `onDevice` on AnalyseRequest; `verdictSource`, `interpretation`, `latencyMs` on AnalyseResponse; `machineTranslated`, `model`, `promptVersion`, `latencyMs` on TranslateResponse; new types `VerdictSource` and `Interpretation`. Code regenerated for all four languages.
- [x] Sentinel sends `onDevice` and ignores `verdictSource: on_device` answers
- [x] `kuuma-llm`: Ollama client, `SunflowerClient`, scrubbing guard, placeholder checks and tag mapping, one-line messages, `<think>` stripping, prompt registry with `translate@1` and `interpret@1`, cache, language gate, config
- [x] AI service: `POST /v1/ai/translate`, `POST /v1/ai/analyse` phase 1, model gate with priorities, queue limit, budgets and error codes, warm-up, `/healthz/model`
- [x] Unit and service tests with a fake model
- [x] Ollama setup script, Modelfile template and smoke test for SURFACE-02

**Step 1 — Prove it on the Surface**

- [x] Run the setup script and the smoke test for Q4\_K\_M (Nov 6; timings in Reliability)
- [x] Rewrite the prompts after the measurements (Sunbird phrasing, Sunflower's own tags)
- [ ] Measure Q5\_K\_M, and repeat with the emulator running on software graphics
- [ ] Revisit the budgets once the evaluation set runs

**Step 2 — Connect the modules**

- [ ] Casefile: call `/v1/ai/translate` for non-English exhibits and print the translation, labelled machine-made, in the case file
- [ ] Sentinel: decide what the phone does with an interpretation before phase 2 (today it is ignored)
- [ ] Device-signature checks on `/v1/ai` (the stub never checked them)
- [ ] Show both at integration day

**Step 3 — Evaluate and decide**

- [ ] Run the evaluation set; pick Q4\_K\_M or Q5\_K\_M
- [ ] Replace the language gate's word list with the lexicon

**Then:** integrate Gemma 4 as the reasoning model (analyse phase 2), and draft localised advice packs for review.

## Open questions

- [ ] Q4\_K\_M or Q5\_K\_M as the default: Q4 is measured (median 5.6 s); Q5 is not yet
- [ ] Interpretations so far are close to translations: they give the meaning but do not name the harm. Is that enough input for the reasoning model, or does `interpret@2` need to ask for it explicitly (evaluate before changing)?
- [ ] Is a 45-second budget acceptable for the analyse interpretation step, given that alerts never wait for it?
- [ ] Who are the native-speaker reviewers for Luganda, Runyankole and Swahili in the evaluation set?
- [ ] Names the phone does not know reach the model: is that acceptable for the pilot, or is a name detector needed?
- [ ] When Sunflower-Qwen3.5-9B leaves preview, does it replace Sunflower-14B? It would fit the 6 GB GPU better.

## Sources

- [Sunflower-14B GGUF — Sunbird SALT docs](https://salt.sunbird.ai/models/sunflower-14b-gguf/) (Modelfile, quantisations)
- [Sunflower-14B — Sunbird SALT docs](https://salt.sunbird.ai/models/sunflower-14b/) (system message, languages, evaluation)
- [Sunflower-Qwen3.5-9B — Sunbird SALT docs](https://salt.sunbird.ai/models/sunflower-qwen3.5-9b/) (preview model, translation prompt format)
- [Sunbird/Sunflower-14B-GGUF on Hugging Face](https://huggingface.co/Sunbird/Sunflower-14B-GGUF) (public; Modelfile and GGUF files)
