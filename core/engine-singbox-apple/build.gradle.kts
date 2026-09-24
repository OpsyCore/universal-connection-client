plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * Apple sing-box engine (Phase 7 preparation).
 *
 *   engine-api ── CoreAdapter/CoreFactory/CorePlatform
 *        ↓
 *   this module ── AppleSingBoxTunnelEngine → AppleSingBoxCoreAdapter → LibboxService (boundary)
 *        ↓
 *   iosMain ── the only place a Libbox.xcframework cinterop may ever be referenced.
 *
 * commonMain is framework-free and JVM-tested. No Libbox.xcframework is linked: the
 * iosMain LibboxServiceFactory reports Unavailable until the real framework (sing-box
 * v1.13.21, see core/engine-singbox/libbox-apple.sha256) is obtained and pinned.
 */
kotlin {
    jvmToolchain(17)
    explicitApi()
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:engine-api"))
            api(project(":core:singbox-config"))
            api(project(":core:ios-vpn"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
