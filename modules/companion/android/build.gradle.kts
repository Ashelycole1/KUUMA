// Companion-owned Android library: the Nilinde Keyboard and the module that drives the victim screens.
// Rule 1: depend on shared packages only (core-android, contracts, ai-runtime-android), never on another module.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ug.kuuma.companion"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Strings and flags carry the companion_ prefix so they never clash in the merged app.
    resourcePrefix = "companion_"
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
    implementation(project(":core-android"))
    // The AI build's on-device interfaces. DummyReplyRiskChecker is the default until the real
    // checker is injected through CompanionModule.replyChecker; this code doesn't change then.
    implementation(project(":ai-runtime-android"))
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":contracts")))
}
