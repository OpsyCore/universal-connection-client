package io.ucc.core.vpn

import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local rendezvous between [AndroidTunnelHost] (which asks Android to
 * start the service) and the [UcVpnService] instance the system creates.
 * Also gives the service access to the ConnectionManager for notifications
 * and for the "restarted by system" path.
 */
public object VpnServiceRegistry {
    @Volatile internal var instance: UcVpnService? = null
    @Volatile internal var waiter: CompletableDeferred<UcVpnService>? = null

    /** Wired by the application at startup; read by the service. */
    @Volatile public var connectionManager: ConnectionManager? = null
    @Volatile public var stateForNotification: StateFlow<ConnectionState>? = null
    @Volatile public var profileNameLookup: (suspend (String) -> String?)? = null
    @Volatile public var lastProfileStore: LastProfileStore? = null
    @Volatile public var launchIntentFactory: ((android.content.Context) -> android.content.Intent)? = null

    /**
     * What Android reports about always-on / lockdown ("Block connections
     * without VPN") for this app. Only knowable from inside a running
     * [android.net.VpnService] on API 29+; null until the service has run once
     * in this process, and never guessed.
     */
    public val lockdownStatus: kotlinx.coroutines.flow.MutableStateFlow<LockdownStatus?> = kotlinx.coroutines.flow.MutableStateFlow(null)

    /** Real TUN state as seen by the service: null = no TUN fd open. Contains no secrets (addresses/routes/MTU only). */
    public val tunState: kotlinx.coroutines.flow.MutableStateFlow<TunState?> = kotlinx.coroutines.flow.MutableStateFlow(null)

    /** Emits when the system revokes our VPN. */
    public val revoked: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    internal fun onServiceCreated(service: UcVpnService) {
        instance = service
        waiter?.complete(service)
        waiter = null
    }

    internal fun onServiceDestroyed(service: UcVpnService) {
        if (instance === service) instance = null
    }
}

/** Snapshot of Android's always-on VPN state for this app (API 29+; [supported]=false below that). */
public data class LockdownStatus(
    val supported: Boolean,
    val alwaysOn: Boolean,
    /** "Block connections without VPN" — the only true kill switch on Android. */
    val lockdown: Boolean,
    val observedAtEpochMs: Long,
)

/** What `VpnService.Builder.establish()` was asked for, for the diagnostics screen. */
public data class TunState(
    val fd: Int,
    val mtu: Int,
    val addresses: List<String>,
    val routes: List<String>,
    val excludedRoutes: List<String>,
    val dnsServers: List<String>,
    val includedPackages: Int,
    val excludedPackages: Int,
    val establishedAtEpochMs: Long,
)

/** Minimal persistence for "which profile was active" so a system restart of the service can resume. */
public interface LastProfileStore {
    public fun write(profileId: String?)
    public fun read(): String?
}

internal data class PendingTunnel(val profile: ConnectionProfile)
