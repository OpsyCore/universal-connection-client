plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * Platform primitives shared by every core module: hashing, base64, percent
 * encoding, ids, wall clock, IO dispatcher. commonMain declares the contract;
 * each target supplies the actual. This is the ONLY place a core module may
 * touch a platform API (tools/check-core-boundary.sh enforces it).
 */
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
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
