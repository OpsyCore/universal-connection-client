package io.ucc.app.data

import io.ucc.core.engine.CoreFactory
import io.ucc.core.engine.ThirdPartyNotice

/**
 * Third-party notices shown in Settings → Open-source licences and emitted
 * into release artefacts (Phase 9). Application-level entries are static;
 * engine entries come from the selected [CoreFactory] so a different engine
 * automatically brings its own licence list.
 */
class Notices(private val coreFactory: CoreFactory) {
    val application: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice("Kotlin & kotlinx libraries", "see gradle/libs.versions.toml", "Apache-2.0", "https://kotlinlang.org"),
        ThirdPartyNotice("AndroidX / Jetpack Compose / Material 3", "see gradle/libs.versions.toml", "Apache-2.0", "https://developer.android.com/jetpack"),
    )

    val engine: List<ThirdPartyNotice> get() = coreFactory.notices

    val all: List<ThirdPartyNotice> get() = engine + application

    /** Plain-text rendering for the licences screen and for THIRD_PARTY_NOTICES.txt. */
    fun renderPlainText(): String = buildString {
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
