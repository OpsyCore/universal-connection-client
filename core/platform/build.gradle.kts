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

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
