package io.ucc.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Settings are read by the VpnService on the main thread at start; SharedPreferences (sync) is the right tool. No secrets live here. */
interface SettingsStore {
    val settings: StateFlow<ConnectionSettings>
    fun update(transform: (ConnectionSettings) -> ConnectionSettings)
}

class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("ucc_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<ConnectionSettings> = _settings

    private fun load(): ConnectionSettings =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(ConnectionSettings.serializer(), it) }.getOrNull() } ?: ConnectionSettings()

    @Synchronized
    override fun update(transform: (ConnectionSettings) -> ConnectionSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        prefs.edit().putString(KEY, json.encodeToString(ConnectionSettings.serializer(), next)).apply()
        _settings.value = next
    }

    private companion object { const val KEY = "connection_settings_v1" }
}

class InMemorySettingsStore(initial: ConnectionSettings = ConnectionSettings()) : SettingsStore {
    override val settings = MutableStateFlow(initial)
    override fun update(transform: (ConnectionSettings) -> ConnectionSettings) { settings.value = transform(settings.value) }
}
