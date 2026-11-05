// Sentinel-owned Android library.
// Rule 1: depend on shared packages only (core-android, contracts), never on another module.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ug.kuuma.sentinel"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
    implementation(project(":core-android"))
    implementation(libs.androidx.sqlite)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":contracts")))
    // Core's dummy classifier and scenarios, for tests only.
    testImplementation(project(":ai-runtime-android"))
    testImplementation(project(":fakes"))
}
