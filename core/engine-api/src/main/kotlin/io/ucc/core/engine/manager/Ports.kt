package io.ucc.core.engine.manager

import io.ucc.core.engine.TunProvider
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow

/**
 * Platform side of the tunnel: on Android this is backed by the foreground
 * `VpnService`. Kept as an interface so the state machine is testable on the JVM.
 */
public interface TunnelHost {
    /**
     * Ensures VPN permission, starts the foreground service and returns once it
     * can hand out a TUN descriptor. Throws [io.ucc.core.engine.CoreException]
     * with `VpnPermissionDenied` when the user has not granted permission.
     */
    public suspend fun acquire(profile: ConnectionProfile): TunProvider

    /** Stops the foreground service and closes any TUN it still holds. Idempotent. */
    public suspend fun release()

    /** Emits when the system revokes the VPN (another VPN app, user action). */
    public val revoked: Flow<Unit>
}

public interface ProfileProvider {
    public suspend fun byId(id: String): ConnectionProfile?
}

public sealed class NetworkEvent {
    /** A new default network became usable; [networkKey] identifies it (e.g. "wifi:123"). */
    public data class DefaultChanged(val networkKey: String, val transport: String) : NetworkEvent()
    public data object Lost : NetworkEvent()
}

public interface NetworkMonitor {
    /** Hot flow of default-network transitions. Must not replay history on subscribe except the current network. */
    public val events: Flow<NetworkEvent>
}

public interface Clock {
    public fun nowMs(): Long
}

/** Tunable policy so tests and settings can adjust behaviour without touching the state machine. */
public data class ReconnectPolicy(
    /** URL fetched through the tunnel to confirm the upstream really works. */
    val probeUrl: String = "https://www.gstatic.com/generate_204",
    val probeTimeoutMs: Long = 8_000,
    /** How many probe attempts while in Connecting before giving up. */
    val initialProbeAttempts: Int = 3,
    /** Max reconnect attempts after a network change or core failure. */
    val maxReconnectAttempts: Int = 5,
    /** Backoff base; attempt n waits base * 2^(n-1), capped by [maxBackoffMs]. */
    val backoffBaseMs: Long = 1_000,
    val maxBackoffMs: Long = 15_000,
) {
    public fun backoffFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        return (backoffBaseMs shl shift).coerceAtMost(maxBackoffMs)
    }
}
