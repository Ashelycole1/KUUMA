# App shells (Core)

`child-app/`: Flutter UI plus an Android host (`android/app/src/main/kotlin/ug/kuuma/child/KuumaRuntime.kt` registers every module). Add a module in two places: its Kotlin half in `KuumaRuntime.modules()` and its Flutter half in `lib/main.dart`. Debug builds add the fakes, the Debug console and the `adb` scenario receiver.

`parent-app/`: Flutter, login placeholder, navigation built from Guardian's `parent.tab` screens.
