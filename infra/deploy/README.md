# Deploy — later

No staging yet: everything runs locally (`make dev`). When a cloud host is chosen
(AWS or Huawei Cloud, with a budget, before the pilot), this folder gets the staging
deployment, deployed from `main` by CI (see the disabled `deploy-staging` job in
`.github/workflows/ci.yml`). Secrets will live in the host's secret store, never in the repo.
