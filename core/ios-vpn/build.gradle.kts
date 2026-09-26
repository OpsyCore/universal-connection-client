plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * iOS Network Extension foundation: typed tunnel configuration, app<->extension IPC,
 * VPN controller abstraction (commonMain, JVM-tested) and the NetworkExtension
 * actuals (iosMain: NEPacketTunnelProvider subclass, NETunnelProviderManager
 * controller, NEPacketTunnelNetworkSettings mapping). No Libbox, no UI.
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
            api(project(":core:ios-infra"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
