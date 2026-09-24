package io.ucc.iosvpn

import kotlinx.coroutines.flow.StateFlow

/** System-level VPN status as exposed by `NEVPNConnection.status`, framework-free. */
public enum class VpnStatus { INVALID, DISCONNECTED, CONNECTING, CONNECTED, REASSERTING, DISCONNECTING }

/**
 * Host-app control surface over `NETunnelProviderManager`. Shared code depends on
 * this interface only; `NetworkExtensionVpnController` (iosMain) is the real
 * implementation and [FakeVpnController] in tests the only other one.
 */
public interface VpnController {
    public val status: StateFlow<VpnStatus>

    /** loadAllFromPreferences + create-or-update + saveToPreferences. Triggers the system permission prompt on first save. */
    public suspend fun install(profileId: String, tunnel: TunnelConfiguration, localizedDescription: String)

    /** removeFromPreferences of our manager, if any. */
    public suspend fun uninstall()

    /** startVPNTunnel(options:); requires a saved configuration. */
    public suspend fun start(profileId: String)

    /** stopVPNTunnel(). */
    public suspend fun stop()

    /** Typed IPC to the running provider; [VpnError.ProviderUnavailable] when there is no session. */
    public val ipc: IpcClient?
}

/** Centralised identifiers; the Xcode targets must use exactly these (see docs/IOS_NETWORK_EXTENSION.md). */
public data class AppleTargetIds(
    val appGroup: String,
    val tunnelBundleId: String,
    val keychainAccessGroup: String,
) {
    public companion object {
        /** Bundle prefix used by the iOS targets; change here and in the entitlement files together. */
        public const val BUNDLE_PREFIX: String = "io.ucc.ios"
        public val Default: AppleTargetIds = AppleTargetIds(
            appGroup = "group.$BUNDLE_PREFIX",
            tunnelBundleId = "$BUNDLE_PREFIX.tunnel",
            keychainAccessGroup = "\$(AppIdentifierPrefix)group.$BUNDLE_PREFIX",
        )
    }
}
