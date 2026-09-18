plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Shared application layer: use-cases and orchestration that sit between any
 * UI (Android Compose today, iOS later) and the core modules. Pure Kotlin —
 * no Android, no JVM-only APIs, no UI toolkit (tools/check-core-boundary.sh).
 * Platform infrastructure (encrypted storage, preferences, tunnel host) is
 * reached only through the interfaces in Stores.kt and core:engine-api.
 */
kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:engine-api"))
            api(project(":core:config"))
            api(project(":core:smart"))
            api(project(":core:platform"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
