# Shared Android AI runtime

`ug.kuuma.ai.runtime.knit.KnitMessageChecker` is the real English-only classifier
ported from our Flutter/Kotlin prototype. Dummies are retained for the repository's
existing deterministic scenarios. No message text is logged or uploaded here.

```kotlin
val checker = KnitMessageChecker(applicationContext)
val check = checker.check(text, language)
when (check.status) {
    CheckStatus.VALID -> { /* present the check.needsReview result */ }
    else -> { /* NOT CHECKED; keep reporting available */ }
}
// Close on the same serial worker when done. Never run checks on the UI thread.
checker.close()
```

Set up assets with `ai/models/knit/setup-knit.ps1`. The child host must keep `.tflite`
assets uncompressed (`androidResources { noCompress += "tflite" }`) when bundling them.
The model has no runtime download or network dependency.

## Child and parent integration

- **Child phone:** owns incoming text and keyboard checks. Model code lives here.
- **Parent phone:** uses Guardian's existing consent/pairing and permitted summaries;
  it does not need a second copy of the model or unrestricted chat access.
- **Luganda:** use the existing redacted Sunflower translation/interpretation service
  for language work and have fluent reviewers label synthetic examples. Translation
  currently does not produce a new harm verdict.

`KnitHarmClassifier(checker)` implements the existing `HarmClassifier` interface as
an **opt-in adapter**. It accepts only explicit English and throws a content-free
`CheckUnavailableException` for unknown/unsupported input or model failure.
The application's current language guesser is heuristic and cannot establish English
for arbitrary mixed-language messages. Do not auto-select this adapter without
handling abstention in the caller. Default child/parent app wiring remains unchanged
on this branch, so the tests/fake scenarios are not silently swapped to a different model.

The adapter's contract confidence is a raw score transformation. Sentinel's policy
threshold still applies; this is not calibrated severity or risk prediction. Harmful
results map to MEDIUM; a high score alone does not establish severity.

## Next integration gate

The shared `Verdict` contract has only `harmful` and `confidence` and lacks a typed
not-checked status. The child UI/pipeline must handle `MessageCheck` abstentions before
using this classifier by default; a contract change requires the normal contract PR
and owner review. Do not convert unavailable/unsupported results into `harmful=false`.
Keep the parent flow consent-based and leave unrelated module behavior unchanged.
