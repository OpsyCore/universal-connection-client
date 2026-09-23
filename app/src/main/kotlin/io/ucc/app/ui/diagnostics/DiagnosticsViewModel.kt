package io.ucc.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.applogic.ConnectionSettings
import io.ucc.applogic.LogBuffer
import io.ucc.applogic.ProfileStore
import io.ucc.applogic.SelectionStore
import io.ucc.applogic.SettingsStore
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.UnderlyingNetwork
import io.ucc.core.engine.manager.ConnectionEvent
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.vpn.LockdownStatus
import io.ucc.core.vpn.TunState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Read-only snapshot for the device-test Diagnostics screen. Everything here
 * is either already public UI state or derived from it; nothing is a secret:
 * the selected profile is identified by protocol/host/port + fingerprint
 * prefix only (no credentials), and last error is the class + redacted detail.
 */
data class DiagnosticsUiState(
    val coreLabel: String = "",
    val capabilities: CoreCapabilities? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val tun: TunState? = null,
    val network: UnderlyingNetwork? = null,
    val lockdown: LockdownStatus? = null,
    val statistics: CoreStatistics? = null,
    /** "VLESS host:443 · fp=ab12cd34" — no secrets. */
    val selectedProfile: String? = null,
    val selectedProfileSupported: Boolean? = null,
    val settings: ConnectionSettings = ConnectionSettings(),
    val reconnectEvents: Int = 0,
    val networkEvents: Int = 0,
    val errorEvents: Int = 0,
    val lastError: String? = null,
    val logCount: Int = 0,
)

class DiagnosticsViewModel(
    manager: ConnectionManager,
    profiles: ProfileStore,
    selection: SelectionStore,
    settings: SettingsStore,
    logs: LogBuffer,
    tun: StateFlow<TunState?>,
    network: StateFlow<UnderlyingNetwork?>,
    lockdown: StateFlow<LockdownStatus?>,
    private val capabilities: CoreCapabilities,
    private val coreLabel: String,
    private val supported: (io.ucc.core.model.ConnectionProfile) -> Boolean,
) : ViewModel() {

    private data class Counters(val reconnect: Int, val network: Int, val error: Int, val lastError: String?)

    private val counters: StateFlow<Counters> = logs.entries.map { entries ->
        Counters(
            reconnect = entries.count { it.category == LogBuffer.Category.RECONNECT },
            network = entries.count { it.category == LogBuffer.Category.NETWORK },
            error = entries.count { it.level == LogBuffer.Level.ERROR },
            lastError = entries.lastOrNull { it.level == LogBuffer.Level.ERROR }?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counters(0, 0, 0, null))

    private val platform = combine(tun, network, lockdown, manager.statistics) { t, n, l, s -> Quad(t, n, l, s) }
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    val state: StateFlow<DiagnosticsUiState> = combine(
        manager.state, platform, combine(profiles.profiles, selection.selectedProfileIdFlow) { ps, id -> ps.firstOrNull { it.id == id } },
        settings.settings, combine(counters, logs.entries) { c, e -> c to e.size },
    ) { conn, (t, n, l, stats), profile, s, (c, logCount) ->
        DiagnosticsUiState(
            coreLabel = coreLabel, capabilities = capabilities, connection = conn, tun = t, network = n, lockdown = l, statistics = stats,
            selectedProfile = profile?.let { "${it.protocol.name} ${it.address}:${it.port} · fp=${it.fingerprint.take(8)}" },
            selectedProfileSupported = profile?.let(supported),
            settings = s,
            reconnectEvents = c.reconnect, networkEvents = c.network, errorEvents = c.error, lastError = c.lastError,
            logCount = logCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState(coreLabel = coreLabel, capabilities = capabilities))

    /** Plain-text dump for the tester to paste into a bug report. Built only from [state] fields (already secret-free). */
    fun exportText(): String = state.value.let { s ->
        buildString {
            appendLine("core: ${s.coreLabel}")
            appendLine("connection: ${s.connection}")
            appendLine("selected: ${s.selectedProfile ?: "none"} supported=${s.selectedProfileSupported}")
            appendLine("tun: ${s.tun?.let { "fd=${it.fd} mtu=${it.mtu} addr=${it.addresses} routes=${it.routes} excl=${it.excludedRoutes} dns=${it.dnsServers} inc=${it.includedPackages} exc=${it.excludedPackages}" } ?: "closed"}")
            appendLine("network: ${s.network?.let { "${it.interfaceName}#${it.interfaceIndex} expensive=${it.expensive}" } ?: "none"}")
            appendLine("lockdown: ${s.lockdown ?: "unknown"}")
            appendLine("stats: ${s.statistics}")
            appendLine("settings: remoteDns=${s.settings.remoteDns} directDns=${s.settings.directDns ?: "local"} ipv6=${s.settings.ipv6} strict=${s.settings.strictRoute} tlsFragment=${s.settings.tlsFragment} blockQuic=${s.settings.blockQuic} bypassPrivate=${s.settings.bypassPrivate} mtu=${s.settings.mtu} perApp=${s.settings.perAppMode}(${s.settings.perAppPackages.size}) rules=${s.settings.rules.count { it.enabled }}/${s.settings.rules.size} logLevel=${s.settings.logLevel}")
            appendLine("events: reconnect=${s.reconnectEvents} network=${s.networkEvents} error=${s.errorEvents} logs=${s.logCount}")
            appendLine("lastError: ${s.lastError ?: "none"}")
        }
    }

    class Factory(
        private val manager: ConnectionManager, private val profiles: ProfileStore, private val selection: SelectionStore, private val settings: SettingsStore,
        private val logs: LogBuffer, private val tun: StateFlow<TunState?>, private val network: StateFlow<UnderlyingNetwork?>, private val lockdown: StateFlow<LockdownStatus?>,
        private val capabilities: CoreCapabilities, private val coreLabel: String, private val supported: (io.ucc.core.model.ConnectionProfile) -> Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DiagnosticsViewModel(manager, profiles, selection, settings, logs, tun, network, lockdown, capabilities, coreLabel, supported) as T
    }
}
