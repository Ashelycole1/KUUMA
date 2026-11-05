# Shared debug signing key

`kuuma-debug.keystore` signs every **debug** APK, so builds from different laptops can
update each other on the shared test phone without uninstalling.

- Store and key password: `android`. Alias: `androiddebugkey`.
- It is a debug key: **public by design** and never used for releases.
- Release signing keys are never committed. They will live in CI secrets once a store
  account exists.
