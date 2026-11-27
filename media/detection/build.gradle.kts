// Media discovery engine. Pure Kotlin so it can be unit tested on the JVM and
// shared with future iOS / desktop builds. Network access is abstracted behind
// ManifestFetcher so this module has no HTTP dependency of its own.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
