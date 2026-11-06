# Evaluation

Evaluation sets and harnesses, reported per language (en, lg, nyn, sw) and per harm category.

## Luganda work to do on this laptop

`fixtures/lg-pilot-review.json` contains invented Luganda/mixed-language examples.
All labels are intentionally pending. Have a fluent reviewer check spelling, meaning,
context and the intended review decision before setting `review_status: reviewed`
and `expected_label: NEEDS_REVIEW` or `NO_FLAG`. Reported/quoted harm is reviewed too;
the flag never determines blame. Add a separate held-out set before comparing models.

The existing Sunflower service can translate and explain redacted text, but
`/v1/ai/analyse` still passes its input phone verdict through unchanged. Translation
success is not harm-classification accuracy. The English Knit model must not clear
Luganda, code-switched or unknown-language text.

Predictions are a local JSON array containing `id`, `label`, and `output_status`.
Do not put message text in predictions or logs. Example format:

```json
[{"id": "lg-greeting", "label": null, "output_status": "UNSUPPORTED_LANGUAGE"}]
```

```sh
uv run python ai/eval/score_reviewed.py ai/eval/fixtures/lg-pilot-review.json path/to/predictions.json
uv run pytest ai/eval/tests packages/kuuma-llm/tests ai/service/tests
```

The scorer excludes unreviewed labels, returns null accuracy when none are reviewed,
and counts missing/invalid checks as unsuccessful. It never calls a model or prints
message content. Use synthetic data only; do not commit children's chats.
