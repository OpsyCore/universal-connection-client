package io.ucc.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.app.data.ConnectionSettings
import io.ucc.app.data.SettingsStore
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.manager.ConnectionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

data class SettingsUiState(
    val settings: ConnectionSettings = ConnectionSettings(),
    val problems: List<ConnectionSettings.Problem> = emptyList(),
    val capabilities: CoreCapabilities? = null,
    /** Changes apply on the next connect; while connected we tell the user to reconnect. */
    val tunnelActive: Boolean = false,
    val coreLabel: String = "",
)

/**
 * Settings are applied at the next connect (the manager reads them through
 * StartOptionsProvider). No option is shown that the active core cannot honour:
 * per-app routing is gated on [CoreCapabilities.perAppRouting].
 */
class SettingsViewModel(
    private val store: SettingsStore,
    manager: ConnectionManager,
    capabilities: CoreCapabilities,
    coreLabel: String,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(store.settings, manager.state) { s, conn ->
        SettingsUiState(
            settings = s,
            problems = s.validate(),
            capabilities = capabilities,
            tunnelActive = conn !is ConnectionState.Disconnected && conn !is ConnectionState.Error,
            coreLabel = coreLabel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(capabilities = capabilities, coreLabel = coreLabel))

    fun update(transform: (ConnectionSettings) -> ConnectionSettings) = store.update(transform)

    fun setRemoteDns(v: String) = update { it.copy(remoteDns = v.trim()) }
    fun setDirectDns(v: String) = update { it.copy(directDns = v.trim().ifEmpty { null }) }
    fun setBypassPrivate(v: Boolean) = update { it.copy(bypassPrivate = v) }
    fun setIpv6(v: Boolean) = update { it.copy(ipv6 = v) }
    fun setStrictRoute(v: Boolean) = update { it.copy(strictRoute = v) }
    fun setMtu(v: String) { v.toIntOrNull()?.let { m -> update { it.copy(mtu = m) } } }
    fun setLogLevel(v: ConnectionSettings.LogLevel) = update { it.copy(logLevel = v) }
    fun setPerAppMode(v: ConnectionSettings.PerAppMode) = update { it.copy(perAppMode = v) }
    fun togglePackage(pkg: String) = update { it.copy(perAppPackages = if (pkg in it.perAppPackages) it.perAppPackages - pkg else it.perAppPackages + pkg) }
    fun resetDefaults() = update { ConnectionSettings() }

    class Factory(
        private val store: SettingsStore,
        private val manager: ConnectionManager,
        private val capabilities: CoreCapabilities,
        private val coreLabel: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(store, manager, capabilities, coreLabel) as T
    }
}
