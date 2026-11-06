# AI — owned by the AI owner

Skeleton from Core. Everything model-, prompt- and LLM-related lives here (or in
`packages/kuuma-llm`, `packages/ai-runtime-android`). The boundary check fails any of it elsewhere.

| Folder | For |
| --- | --- |
| `service/` | The `/v1/ai` service. Today it serves the OpenAPI examples (`packages/contracts/openapi/ai.yaml`). |
| `models/` | Model cards and export scripts. Large weights go to MinIO/object storage, not git. |
| `training/` | Training and fine-tuning code. |
| `prompts/` | Versioned prompts (`<id>/<version>.prompt.yaml`), loaded through `kuuma_llm.PromptRegistry`. |
| `eval/` | Evaluation sets and harnesses (per language: en, lg, nyn, sw). |
| `datasets/` | Dataset manifests and licences only. No real children's messages, ever. |

The lexicon lives in `packages/lang/lexicon/` (format documented there).
On-device: swap `DummyHarmClassifier` / `DummyReplyRiskChecker` in `packages/ai-runtime-android`
for real models behind the same `HarmClassifier` / `ReplyRiskChecker` interfaces.

## Prototype AI contribution

The phone-tested English Knit classifier is now available in
`packages/ai-runtime-android` as an opt-in typed checker and contract adapter.
See that package's README and `ai/models/knit/README.md` for setup, limits and
the remaining child-app integration gate. Large weights are not committed.

For the two apps, the child phone owns message inference; the parent phone uses
Guardian's existing pairing/sharing workflow. There is no need to duplicate the
classifier on the parent phone. Existing app wiring stays on the development
dummies until unsupported-language/not-checked results are handled in the UI.

Luganda routing now sends unknown-language text to the existing Sunflower language
path instead of assuming it is English. `ai/eval` provides a human-review fixture
and scoring harness. Neither the port nor this gate change establishes Luganda
harm accuracy; `/v1/ai/analyse` currently adds interpretation without a new verdict.
