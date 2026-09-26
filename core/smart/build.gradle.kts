plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin. Depends on the normalized model and the engine-neutral API only —
// never on an engine module (see tools/check-core-boundary.sh).
kotlin {
    jvmToolchain(17)
    explicitApi()
    jvm()
    // Apple targets: klib cross-compiled on Linux CI (Kotlin ≥ 2.2.20 default, no cinterop). Final
    // binaries / test execution need a macOS host — see docs/KMP_IOS.md.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:engine-api"))
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
