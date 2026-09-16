package io.ucc.app.core

import android.content.Context
import io.ucc.app.BuildConfig
import io.ucc.core.engine.CoreFactory
import io.ucc.core.singbox.android.SingBoxCoreFactory

/**
 * Flavour source set `singbox`: the single place in the application that
 * knows a concrete engine. `src/main` never imports an engine module.
 *
 * Adding a new engine = new module implementing [CoreFactory] + a new flavour
 * with its own `src/<flavour>/kotlin/io/ucc/app/core/CoreFactories.kt`.
 */
object CoreFactories {
    const val SINGBOX = "singbox"

    /** All engines compiled into this build. */
    fun available(context: Context): List<CoreFactory> = listOf(
        SingBoxCoreFactory(context),
    )

    /** The engine chosen for this build. */
    fun selected(context: Context): CoreFactory {
        val wanted = BuildConfig.CORE_ID
        return available(context).firstOrNull { it.id == wanted }
            ?: error("CORE_ID '$wanted' is not compiled into this build; available: ${available(context).map { it.id }}")
    }
}
