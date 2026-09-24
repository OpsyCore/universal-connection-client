package io.ucc.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.applogic.ConnectionSettings
import io.ucc.applogic.LogBuffer
import io.ucc.app.data.Notices
import io.ucc.applogic.SettingsStore
import io.ucc.app.data.ThemeMode
import io.ucc.app.data.ThemeStore
import io.ucc.applogic.AppLanguage
import io.ucc.applogic.LanguageStore
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.RouteAction
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.vpn.LockdownStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

data class SettingsUiState(
    val settings: ConnectionSettings = ConnectionSettings(),
    val problems: List<ConnectionSettings.Problem> = emptyList(),
    val capabilities: CoreCapabilities? = null,
    /** Changes apply on the next connect; while connected we tell the user to reconnect. */
    val tunnelActive: Boolean = false,
    val coreLabel: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** null = Android has not told us yet (service never ran in this process). Never guessed. */
    val lockdown: LockdownStatus? = null,
    val appVersion: String = "",
    val appBuild: String = "",
    val sourceUrl: String = "",
    val logCount: Int = 0,
)

/**
 * Settings are applied at the next connect (the manager reads them through
 * StartOptionsProvider). No option is shown that the active core cannot honour:
 * per-app routing is gated on [CoreCapabilities.perAppRouting]. There is no
 * kill-switch toggle: the only OS-enforced kill switch is Android's always-on
 * lockdown, whose real state is surfaced read-only from [LockdownStatus].
 */
class SettingsViewModel(
    private val store: SettingsStore,
    manager: ConnectionManager,
    capabilities: CoreCapabilities,
    coreLabel: String,
    private val themeStore: ThemeStore,
    private val logBuffer: LogBuffer,
    val notices: Notices,
    lockdown: StateFlow<LockdownStatus?>,
    appVersion: String,
    private val languageStore: LanguageStore = LanguageStore.InMemory(),
    appBuild: String = "",
    sourceUrl: String = "",
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(store.settings, manager.state, themeStore.themeFlow, lockdown, logBuffer.entries) { s, conn, theme, lock, logs ->
        SettingsUiState(
            settings = s,
            problems = s.validate(),
            capabilities = capabilities,
            tunnelActive = conn !is ConnectionState.Disconnected && conn !is ConnectionState.Error,
            coreLabel = coreLabel,
            theme = theme,
            lockdown = lock,
            appVersion = appVersion,
            appBuild = appBuild,
            sourceUrl = sourceUrl,
            logCount = logs.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(capabilities = capabilities, coreLabel = coreLabel, appVersion = appVersion, appBuild = appBuild, sourceUrl = sourceUrl))

    fun update(transform: (ConnectionSettings) -> ConnectionSettings) = store.update(transform)

    fun setRemoteDns(v: String) = update { it.copy(remoteDns = v.trim()) }
    fun setDirectDns(v: String) = update { it.copy(directDns = v.trim().ifEmpty { null }) }
    fun setBypassPrivate(v: Boolean) = update { it.copy(bypassPrivate = v) }
    fun setIpv6(v: Boolean) = update { it.copy(ipv6 = v) }
    fun setStrictRoute(v: Boolean) = update { it.copy(strictRoute = v) }
    fun setTlsFragment(v: Boolean) = update { it.copy(tlsFragment = v) }
    fun setBlockQuic(v: Boolean) = update { it.copy(blockQuic = v) }
    fun setFakeDns(v: Boolean) = update { it.copy(fakeDns = v) }
    fun setNotificationSpeed(v: Boolean) = update { it.copy(notificationSpeed = v) }
    fun setAutoConnectOnBoot(v: Boolean) = update { it.copy(autoConnectOnBoot = v) }
    fun setDirectIran(v: Boolean) = update { it.copy(directIran = v) }
    fun setBlockAds(v: Boolean) = update { it.copy(blockAds = v) }
    fun setRealDelayTest(v: Boolean) = update { it.copy(realDelayTest = v) }
    fun setDelayTestUrl(v: String) = update { it.copy(delayTestUrl = v.trim()) }
    fun setSubscriptionUpdateIntervalHours(v: Int) = update { it.copy(subscriptionUpdateIntervalHours = v) }
    fun setSubscriptionUpdateOnOpen(v: Boolean) = update { it.copy(subscriptionUpdateOnOpen = v) }
    fun setMtu(v: String) { v.toIntOrNull()?.let { m -> update { it.copy(mtu = m) } } }
    fun setLogLevel(v: ConnectionSettings.LogLevel) = update { it.copy(logLevel = v) }
    fun setPerAppMode(v: ConnectionSettings.PerAppMode) = update { it.copy(perAppMode = v) }
    fun togglePackage(pkg: String) = update { it.copy(perAppPackages = if (pkg in it.perAppPackages) it.perAppPackages - pkg else it.perAppPackages + pkg) }
    fun resetDefaults() = update { ConnectionSettings() }

    // ---- routing rules
    fun addRule(action: RouteAction, itemsText: String) {
        val items = parseItems(itemsText)
        if (items.isEmpty()) return
        update { it.copy(rules = it.rules + ConnectionSettings.Rule(UUID.randomUUID().toString(), action, items)) }
    }
    fun editRule(id: String, action: RouteAction, itemsText: String) {
        val items = parseItems(itemsText)
        update { s -> s.copy(rules = s.rules.map { if (it.id == id) it.copy(action = action, items = items) else it }) }
    }
    fun removeRule(id: String) = update { s -> s.copy(rules = s.rules.filter { it.id != id }) }
    fun toggleRule(id: String) = update { s -> s.copy(rules = s.rules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }) }
    fun moveRule(id: String, up: Boolean) = update { s ->
        val i = s.rules.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in s.rules.indices) s else s.copy(rules = s.rules.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun setTheme(mode: ThemeMode) { themeStore.theme = mode }

    /** Language is applied and persisted by [LanguageStore] (AppCompat per-app locales in production). */
    fun setLanguage(language: AppLanguage) { languageStore.language = language }
    val language: StateFlow<AppLanguage> get() = languageStore.languageFlow
    fun clearLogs() = logBuffer.clear()

    class Factory(
        private val store: SettingsStore,
        private val manager: ConnectionManager,
        private val capabilities: CoreCapabilities,
        private val coreLabel: String,
        private val themeStore: ThemeStore,
        private val logBuffer: LogBuffer,
        private val notices: Notices,
        private val lockdown: StateFlow<LockdownStatus?>,
        private val appVersion: String,
        private val languageStore: LanguageStore,
        private val appBuild: String,
        private val sourceUrl: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(store, manager, capabilities, coreLabel, themeStore, logBuffer, notices, lockdown, appVersion, languageStore, appBuild, sourceUrl) as T
    }

    companion object {
        /** Comma/newline/space separated, deduplicated, order kept. */
        fun parseItems(text: String): List<String> = text.split('\n', ',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
