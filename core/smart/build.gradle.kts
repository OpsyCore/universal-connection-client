plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

// Pure Kotlin. Depends on the normalized model and the engine-neutral API only —
// never on an engine module (see tools/check-core-boundary.sh).
dependencies {
    api(project(":core:model"))
    api(project(":core:engine-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
