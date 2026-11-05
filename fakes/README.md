# Fakes

`scenarios/` holds scripted chats (invented text only). `android/` holds the phone fakes (`FakeSentinel` runs the HarmClassifier; the others react in chain) and the scenario player used by the child app's debug build. `python/` holds the laptop twin and the `kuuma-fake` CLI behind `make fake`.

Add a scenario: write `scenarios/<name>.yaml`, run `make gen`, then `make test` here. Tests check that the dummy classifier agrees with every `expect:`.
