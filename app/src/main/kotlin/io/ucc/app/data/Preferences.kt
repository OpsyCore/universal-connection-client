package io.ucc.app.data

import android.content.Context
import android.content.SharedPreferences
import io.ucc.core.vpn.LastProfileStore

/**
 * Tiny synchronous preference holder for values the VpnService must read on
 * its main thread during `onStartCommand` (DataStore would require blocking).
 * Only non-secret identifiers are stored here.
 */
/** Selection state shared by Home and Servers; abstracted so view-models are testable without a Context. */
interface SelectionStore {
    val selectedProfileIdFlow: kotlinx.coroutines.flow.StateFlow<String?>
    var selectedProfileId: String?

    /** true = Smart selection picks the server on connect; false = the user's [selectedProfileId] is used. */
    val smartModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
    var smartMode: Boolean
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

interface ThemeStore {
    val themeFlow: kotlinx.coroutines.flow.StateFlow<ThemeMode>
    var theme: ThemeMode
}

class Preferences(context: Context) : LastProfileStore, SelectionStore, ThemeStore {
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

    override fun write(profileId: String?) {
        prefs.edit().putString(KEY_LAST_ACTIVE, profileId).commit()
    }

    override fun read(): String? = prefs.getString(KEY_LAST_ACTIVE, null)

    private companion object {
        const val KEY_SELECTED = "selected_profile_id"
        const val KEY_LAST_ACTIVE = "last_active_profile_id"
        const val KEY_THEME = "theme_mode"
        const val KEY_SMART = "smart_selection"
    }
}
