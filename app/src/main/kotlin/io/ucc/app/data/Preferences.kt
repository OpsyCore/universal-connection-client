package io.ucc.app.data

import android.content.Context
import android.content.SharedPreferences
import io.ucc.applogic.SelectionStore
import io.ucc.core.vpn.LastProfileStore

/**
 * Tiny synchronous preference holder for values the VpnService must read on
 * its main thread during `onStartCommand` (DataStore would require blocking).
 * Only non-secret identifiers are stored here.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

interface ThemeStore {
    val themeFlow: kotlinx.coroutines.flow.StateFlow<ThemeMode>
    var theme: ThemeMode
}

class Preferences(context: Context) : LastProfileStore, SelectionStore, ThemeStore, io.ucc.applogic.ServerListPrefs {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("ucc_prefs", Context.MODE_PRIVATE)

    private val _selected = kotlinx.coroutines.flow.MutableStateFlow(prefs.getString(KEY_SELECTED, null))

    /** Observed by Home and Servers so a selection made on one screen shows on the other. */
    override val selectedProfileIdFlow: kotlinx.coroutines.flow.StateFlow<String?> = _selected

    override var selectedProfileId: String?
        get() = _selected.value
        set(value) { _selected.value = value; prefs.edit().putString(KEY_SELECTED, value).apply() }

    private val _smart = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean(KEY_SMART, false))
    override val smartModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _smart
    override var smartMode: Boolean
        get() = _smart.value
        set(value) { _smart.value = value; prefs.edit().putBoolean(KEY_SMART, value).apply() }

    private val _theme = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
    )
    override val themeFlow: kotlinx.coroutines.flow.StateFlow<ThemeMode> = _theme
    override var theme: ThemeMode
        get() = _theme.value
        set(value) { _theme.value = value; prefs.edit().putString(KEY_THEME, value.name).apply() }

    private val _sortLatency = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean(KEY_SORT_LATENCY, false))
    override val sortByLatencyFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _sortLatency
    override var sortByLatency: Boolean
        get() = _sortLatency.value
        set(value) { _sortLatency.value = value; prefs.edit().putBoolean(KEY_SORT_LATENCY, value).apply() }

    private val _hideFailed = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean(KEY_HIDE_FAILED, false))
    override val hideFailedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _hideFailed
    override var hideFailed: Boolean
        get() = _hideFailed.value
        set(value) { _hideFailed.value = value; prefs.edit().putBoolean(KEY_HIDE_FAILED, value).apply() }

    /** v1.0.3: the public free-server subscription is seeded once; deleting it later must stick. */
    var freeSubscriptionSeeded: Boolean
        get() = prefs.getBoolean(KEY_FREE_SUB_SEEDED, false)
        set(value) { prefs.edit().putBoolean(KEY_FREE_SUB_SEEDED, value).apply() }

    override fun write(profileId: String?) {
        prefs.edit().putString(KEY_LAST_ACTIVE, profileId).commit()
    }

    override fun read(): String? = prefs.getString(KEY_LAST_ACTIVE, null)

    private companion object {
        const val KEY_SELECTED = "selected_profile_id"
        const val KEY_LAST_ACTIVE = "last_active_profile_id"
        const val KEY_THEME = "theme_mode"
        const val KEY_SMART = "smart_selection"
        const val KEY_SORT_LATENCY = "servers_sort_latency"
        const val KEY_FREE_SUB_SEEDED = "free_subscription_seeded"
        const val KEY_HIDE_FAILED = "servers_hide_failed"
    }
}
