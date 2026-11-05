// Kuuma Kotlin/Android build. Flutter apps under apps/ include this build (includeBuild)
// so their Android hosts can depend on these libraries.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kuuma"

fun module(path: String, dir: String) {
    include(path)
    project(path).projectDir = file(dir)
}

// Core-owned shared packages
module(":contracts", "packages/contracts")
module(":core-android", "packages/core-android")
module(":core-android-lint", "packages/core-android-lint")
module(":lang", "packages/lang/kotlin")
module(":fakes", "fakes/android")

// AI-owned (skeleton from Core)
module(":ai-runtime-android", "packages/ai-runtime-android")

// Module-owned Android libraries (skeletons from Core)
module(":sentinel", "modules/sentinel/android")
module(":companion", "modules/companion/android")
module(":casefile", "modules/casefile/android")
module(":guardian", "modules/guardian/android")
