package io.ucc.iosinfra

import io.ucc.applogic.AppLanguage
import io.ucc.applogic.ConnectionSettings
import io.ucc.applogic.LanguageStore
import io.ucc.applogic.SelectionStore
import io.ucc.applogic.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Last profile the tunnel was started with, so a relaunched extension/app can
 * re-attach. Declared here (not in core/vpn, which is Android-only) with the
 * same two-method shape as `io.ucc.core.vpn.LastProfileStore`.
 */
public interface LastProfileStore {
    public fun write(profileId: String?)
    public fun read(): String?
}

/**
 * Non-secret preferences over a synchronous [KeyValueStore] (iOS: NSUserDefaults).
 * Keys are identical to the Android `Preferences` (`ucc_prefs`), values are plain
 * strings/booleans — no format to migrate.
 */
public class KeyValuePreferences(private val kv: KeyValueStore) : SelectionStore, LastProfileStore, LanguageStore {
    private val _selected = MutableStateFlow(kv.getString(KEY_SELECTED))
    override val selectedProfileIdFlow: StateFlow<String?> = _selected
    override var selectedProfileId: String?
        get() = _selected.value
        set(value) { _selected.value = value; kv.putString(KEY_SELECTED, value) }

    private val _smart = MutableStateFlow(kv.getBoolean(KEY_SMART, false))
    override val smartModeFlow: StateFlow<Boolean> = _smart
    override var smartMode: Boolean
        get() = _smart.value
        set(value) { _smart.value = value; kv.putBoolean(KEY_SMART, value) }

    private val _language = MutableStateFlow(AppLanguage.fromTag(kv.getString(KEY_LANGUAGE)))
    override val languageFlow: StateFlow<AppLanguage> = _language
    override var language: AppLanguage
        get() = _language.value
        set(value) { _language.value = value; kv.putString(KEY_LANGUAGE, value.tag) }

    override fun write(profileId: String?): Unit = kv.putString(KEY_LAST_ACTIVE, profileId)
    override fun read(): String? = kv.getString(KEY_LAST_ACTIVE)

    public companion object {
        public const val KEY_SELECTED: String = "selected_profile_id"
        public const val KEY_LAST_ACTIVE: String = "last_active_profile_id"
        public const val KEY_SMART: String = "smart_selection"
        /** Android delegates language to the OS (per-app locales); iOS keeps the BCP-47 tag here. */
        public const val KEY_LANGUAGE: String = "app_language"
    }
}

/** [SettingsStore] over a [KeyValueStore]; same JSON and key (`connection_settings_v1`) as Android's `PrefsSettingsStore`. */
public class KeyValueSettingsStore(private val kv: KeyValueStore) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<ConnectionSettings> = _settings

    private fun load(): ConnectionSettings =
        kv.getString(KEY)?.let { runCatching { json.decodeFromString(ConnectionSettings.serializer(), it) }.getOrNull() } ?: ConnectionSettings()

    override fun update(transform: (ConnectionSettings) -> ConnectionSettings) {
        // Single-writer by contract (UI thread); atomic update keeps the flow consistent without a JVM monitor.
        val next = transform(_settings.value)
        if (next == _settings.value) return
        kv.putString(KEY, json.encodeToString(ConnectionSettings.serializer(), next))
        _settings.value = next
    }

    public companion object { public const val KEY: String = "connection_settings_v1" }
}
