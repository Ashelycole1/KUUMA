# English phone classifier from the prototype

This ports our tested Detoxify/ALBERT TFLite runtime into the shared Android AI package.
It detects insults and threats in **English only**, with the prototype's 0.50 maximum
threat/insult cutoff. Its sigmoid outputs are uncalibrated scores, not a measured
probability of bullying. It does not detect every category in Kuuma's contracts.

Setup from the repository root:

```powershell
./ai/models/knit/setup-knit.ps1
```

For an existing verified download, add `-LocalAssets '<folder containing the three files>'`.
Downloads/copies are SHA-256 checked before replacement. Weights and tokenizer are
excluded from git. Keep LICENSE.txt and NOTICE.txt in the APK.

## Provenance

- Export: getknit/knit, commit `915e7021dd3df9563ff033e21f9b3acb977ac4f7`.
- Model: Detoxify unbiased-small, ALBERT; Apache-2.0 upstream attribution retained.
- Model size: 14,652,576 bytes; tokenizer: 1,958,004 bytes.
- Runtime: LiteRT 1.4.2, API 21 minimum in its AAR manifest.
- Input: full text up to 640 characters and 126 body tokens plus CLS/SEP. No prefix-only clearing.
- Model labels/tensor dimensions are validated before inference. Unavailable models never produce a clear verdict.

## Actual earlier prototype observations

Samsung SM-A075F, about 4 GB RAM: 8/8 core, 6/6 additional, 5/8 fresh synthetic
English cases, using the Android runtime. Laptop: 8/8, 6/6, 6/8. These are development
examples, not held-out accuracy estimates. Warm phone checks were about 0.26 seconds;
process PSS was about 158 MiB with the classifier loaded in that diagnostic. This
new shared-package port has not been phone-benchmarked.

Known failures: indirect threats, a threat just below cutoff, and the harmless idiom
“kill the lights”. Luganda, Swahili, Runyankole and mixed-language accuracy are unvalidated.
The Gemini/Gemma/Qwen experiments are not required to run this model.
