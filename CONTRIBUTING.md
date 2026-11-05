# Contributing to Kuuma

These rules keep six builds from colliding. Each one is backed by code, CI or `CODEOWNERS`,
so breaking one fails the build rather than relying on memory.

| # | Rule | Enforced by |
| --- | --- | --- |
| 1 | **No cross-module imports.** A module imports shared packages (`packages/*`), never another module. Only the app shells combine modules. | `tools/boundary_check.py` (in `make check` and CI) |
| 2 | **Own your data.** Each build reads and writes only its own schema and phone database. | Per-schema PostgreSQL roles (`infra/postgres/init.sh`); `ModuleContext.openDatabase()` opens only your file |
| 3 | **Publish only your own topics.** | The phone `EventBus` (`TopicOwnershipException`) and `StreamPublisher` (`TopicOwnershipError`) |
| 4 | **AI lives only in AI folders.** No model files, prompts or LLM/ML SDKs outside `ai/`, `packages/kuuma-llm/` and `packages/ai-runtime-android/`. | `tools/boundary_check.py` |
| 5 | **Contracts change by pull request.** Changes to `packages/contracts/` need the main builder's approval plus every build that publishes or consumes the changed event. Changes are additive; removing or renaming a field means a new event version (`<type>.v2.json`). | `CODEOWNERS`, codegen drift check, contract tests |
| 6 | **Ship a fake.** Every build keeps its fake in `fakes/` working. | Fake tests and the nightly emulator scenario |
| 7 | **Never log message content.** Logs hold IDs, enums, numbers and timings only. | `SafeLog` + the `KuumaSafeLogNonConstant` / `KuumaRawLog` lint checks, `get_logger()` (refuses free text), the CI privacy scan |
| 8 | **Short branches.** Name branches `<build>/<task>` (e.g. `guardian/pairing-api`) and merge into `main` within days, with CI green and the folder owner's approval. | Branch protection on `main` |
| 9 | **Weekly integration.** The main builder leads it: everyone merges, one more fake is swapped for the real part, and the team runs the end-to-end scenario on the local stack and the shared test phone. | Calendar |

## Changing a contract

1. Edit or add a schema in `packages/contracts/schemas/` (events are `events/<type>.v<N>.json`).
   Every object type is named, and every property is a `$ref`, a primitive or an array.
   Non-phone-only events may not have fields like `text`, `name` or `phone`: the generator refuses them.
2. If an API changes, update its file in `packages/contracts/openapi/`, with an example for every
   request and response. The example is the stub's sample data.
3. Run `make gen` and commit the generated code. CI fails if `generated/` is stale.
4. Open a PR. `CODEOWNERS` requests the right reviewers.

## Translations

Keys are `<prefix>.<area>.<name>`, where the prefix is the owner: `core`, `sentinel`,
`companion`, `casefile` or `guardian`. Register your strings with
`KuumaL10n.instance.register('<prefix>', {...})`; registering outside your prefix throws.
English is the source text. Luganda (`lg`), Runyankole (`nyn`) and Swahili (`sw`) are written
by native speakers. Missing keys fall back to English.

## Scenarios and test data

Scenarios (`fakes/scenarios/*.yaml`) and fixtures contain **invented text only**, never a real
message. Stages that would involve sexual content are written as `[PLACEHOLDER: ...]`.
Phone numbers in test data go under `tests/`, and the scanners' own tests are listed in
`tools/privacy_allowlist.txt`.

## Before you push

```sh
make format && make check && make test
```

Pre-commit hooks (installed by `make bootstrap`) run the fast parts on every commit.
