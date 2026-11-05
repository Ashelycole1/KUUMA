plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ug.kuuma.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        // The SafeLog lint check ships with this library and runs on every consumer.
        abortOnError = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
    api(project(":contracts"))
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    lintPublish(project(":core-android-lint"))
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":contracts")))
}
