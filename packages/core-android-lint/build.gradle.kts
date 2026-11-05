// Android Lint checks published with kuuma-core-android (lintPublish), so every module gets them.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)
}

tasks.jar {
    manifest { attributes("Lint-Registry-v2" to "ug.kuuma.lint.KuumaIssueRegistry") }
}
