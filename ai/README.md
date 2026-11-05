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
