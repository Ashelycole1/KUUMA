# Lexicon — owned by the AI owner

This folder is empty on purpose. The AI owner fills it; Core only defines the format.

## Files

One file per language, named by the contract `Language` value:

```
lexicon/
  en.yaml
  lg.yaml
  nyn.yaml
  sw.yaml
```

## Format

```yaml
language: lg              # must equal the file name and a contract Language value
version: 0.1.0            # semver; bump on every change
entries:
  - term: "example"       # the word or phrase, lowercase, as written
    variants: ["exampl3"] # spellings, leetspeak, common typos
    category: insult      # a contract HarmCategory value
    severity: medium      # a contract Severity value
    contextual: false     # true if harmful only in some contexts (needs the model to decide)
    notes: ""             # for reviewers; never shipped to phones
```

## Rules

- `category` and `severity` must be contract enum values. `make test` in `packages/lang` fails otherwise.
- Terms must come from research or annotation guidelines, never copied from real children's messages.
- The lexicon is shipped to phones through `GET /v1/ai/lexicon/latest` (an `ArtifactManifest`), not bundled in the app.
