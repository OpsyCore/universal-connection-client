package io.ucc.iosvpn

import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.manager.Clock
import io.ucc.core.engine.manager.ConnectionManager

/**
 * Host-app side glue. The core runs inside the extension process (as the Android
 * core runs inside the VpnService process), so the app does not drive a second
 * state machine: it *mirrors* the system VPN status into the shared
 * [ConnectionManager] through the existing `attachRunningTunnel`/`disconnect` API,
 * exactly like the Android app does when it binds to an already-running service.
 */
public object VpnStatusMapper {
    public fun toConnectionState(status: VpnStatus, profileId: String?, sinceEpochMs: Long): ConnectionState = when (status) {
        VpnStatus.INVALID, VpnStatus.DISCONNECTED -> ConnectionState.Disconnected
        VpnStatus.CONNECTING -> if (profileId != null) ConnectionState.Connecting(profileId) else ConnectionState.Disconnected
        VpnStatus.CONNECTED -> if (profileId != null) ConnectionState.Connected(profileId, sinceEpochMs) else ConnectionState.Disconnected
        VpnStatus.REASSERTING -> if (profileId != null) ConnectionState.Reconnecting(profileId, attempt = 1, reason = "system reasserting") else ConnectionState.Disconnected
        VpnStatus.DISCONNECTING -> ConnectionState.Stopping(profileId)
    }
}

/**
 * Applies system status changes to the shared manager. Only two operations exist
 * on the manager for "external" tunnels, so only those are used.
 */
public class VpnStatusMirror(
    private val manager: ConnectionManager,
    private val clock: Clock,
    private val currentProfileId: () -> String?,
) {
    private var attached: String? = null

    public fun onStatus(status: VpnStatus) {
        val pid = currentProfileId()
        when (status) {
            VpnStatus.CONNECTED -> if (pid != null && attached != pid) { manager.attachRunningTunnel(pid, clock.nowMs()); attached = pid }
            VpnStatus.DISCONNECTED, VpnStatus.INVALID -> if (attached != null) { manager.disconnect(); attached = null }
            else -> Unit
        }
    }
}
