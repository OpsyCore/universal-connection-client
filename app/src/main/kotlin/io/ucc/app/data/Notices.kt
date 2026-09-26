package io.ucc.app.data

import io.ucc.core.engine.CoreFactory
import io.ucc.core.engine.ThirdPartyNotice

/**
 * Third-party notices shown in Settings → Open-source licenses. Application
 * entries mirror the runtime dependencies declared in `gradle/libs.versions.toml`
 * (test-only artefacts are deliberately absent). Engine entries come from the
 * selected [CoreFactory] so a different engine brings its own list.
 * Keep in sync with THIRD_PARTY_NOTICES.md.
 */
class Notices(private val coreFactory: CoreFactory) {
    val application: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice("Kotlin standard library", "2.2.21", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
        ThirdPartyNotice("kotlinx.coroutines", "1.10.2", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        ThirdPartyNotice("kotlinx.serialization", "1.9.0", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
        ThirdPartyNotice("AndroidX Core, AppCompat, Activity, Lifecycle, Navigation, DataStore, WorkManager", "see libs.versions.toml", "Apache-2.0", "https://github.com/androidx/androidx"),
        ThirdPartyNotice("Jetpack Compose (UI, Material 3, Material Icons)", "BOM 2025.09.01", "Apache-2.0", "https://github.com/androidx/androidx"),
        ThirdPartyNotice("AndroidX CameraX (core, camera2, lifecycle, view)", "1.5.1", "Apache-2.0", "https://github.com/androidx/androidx"),
        ThirdPartyNotice("ML Kit Barcode Scanning (bundled model)", "17.3.0", "Google APIs Terms of Service / ML Kit Terms (proprietary, redistributable binary)", "https://developers.google.com/ml-kit/terms"),
        ThirdPartyNotice("Google Play services basement / play-services-mlkit-barcode-scanning, ML Kit common & vision-common (transitive dependencies of ML Kit)", "18.4.0 / 18.3.1 / 18.11.0 / 17.3.0", "Android Software Development Kit License / ML Kit Terms of Service", "https://developer.android.com/studio/terms"),
        ThirdPartyNotice("Material Components for Android (transitive via AppCompat/Compose)", "per AndroidX POMs", "Apache-2.0", "https://github.com/material-components/material-components-android"),
    )

    val engine: List<ThirdPartyNotice> get() = coreFactory.notices

    val all: List<ThirdPartyNotice> get() = engine + application

    /** Plain-text rendering used by the share/export path and by tests. */
    fun renderPlainText(): String = buildString {
        appendLine("Universal Connection Client — third-party notices")
        appendLine("Engine: ${coreFactory.descriptor.displayName} ${coreFactory.descriptor.version}")
        appendLine()
        for (n in all) {
            appendLine("${n.component} ${n.version}")
            appendLine("  License: ${n.license}")
            appendLine("  ${n.url}")
            n.additionalTerms?.let { appendLine("  Additional terms: $it") }
            appendLine()
        }
    }
}
