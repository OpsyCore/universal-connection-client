plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * iOS platform infrastructure behind the shared interfaces of core/app-logic,
 * core/smart and core/engine-api. Everything that does not need an Apple API
 * (JSON stores, envelope framing, preference mapping) is in commonMain so it is
 * unit-tested on the JVM in Linux CI; iosMain holds only Keychain, CommonCrypto,
 * NSFileManager, NSUserDefaults and nw_path_monitor code. No UI, no VPN.
 */
kotlin {
    jvmToolchain(17)
    explicitApi()
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:app-logic"))
            api(project(":core:smart"))
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
