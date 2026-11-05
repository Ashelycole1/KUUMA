// kuuma-contracts for Kotlin: generated types + hand-written on-device AI interfaces.
// Plain JVM library so it runs in unit tests without Android; Android modules depend on it.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

sourceSets {
    main {
        kotlin.srcDirs("generated/kotlin/src/main/kotlin", "kotlin/main")
        resources.srcDirs("generated/kotlin/src/main/resources")
    }
    test { kotlin.srcDirs("kotlin/test") }
    named("testFixtures") { kotlin.srcDirs("kotlin/testFixtures") }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testFixturesApi(libs.json.schema.validator)
    testFixturesApi(libs.junit)
    testImplementation(libs.junit)
}
