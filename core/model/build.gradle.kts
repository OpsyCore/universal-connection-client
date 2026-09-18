plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

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
            api(project(":core:platform"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)

        }

    }
}
