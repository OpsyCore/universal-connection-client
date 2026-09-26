@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.ucc.iosvpn

import io.ucc.iosinfra.toByteArray
import io.ucc.iosinfra.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.NetworkExtension.NETunnelProviderSession
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusDidChangeNotification
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `NETunnelProviderManager` behind [VpnController]. One manager per app, identified by
 * [AppleTargetIds.tunnelBundleId]; status is observed via NEVPNStatusDidChangeNotification
 * (push, no polling).
 */
public class NetworkExtensionVpnController(
    private val ids: AppleTargetIds = AppleTargetIds.Default,
    private val ipcTimeoutMs: Long = IpcClient.DEFAULT_TIMEOUT_MS,
) : VpnController {
    private val _status = MutableStateFlow(VpnStatus.INVALID)
    override val status: StateFlow<VpnStatus> = _status
    private var manager: NETunnelProviderManager? = null
    private var observer: NSObjectProtocol? = null

    private fun observe(m: NETunnelProviderManager) {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            NEVPNStatusDidChangeNotification, m.connection, NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> _status.value = m.connection.status.toVpnStatus() }
        _status.value = m.connection.status.toVpnStatus()
    }

    /** loadAllFromPreferences → our manager (matched by provider bundle id) or null. */
    public suspend fun load(): NETunnelProviderManager? {
        val all = suspendCancellableCoroutine<List<NETunnelProviderManager>> { cont ->
            NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, error ->
                if (error != null) cont.resumeWithException(VpnError.PermissionOrConfigurationUnavailable("loadAllFromPreferences ${error.code}"))
                else cont.resume(managers?.filterIsInstance<NETunnelProviderManager>() ?: emptyList())
            }
        }
        val mine = all.firstOrNull { (it.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == ids.tunnelBundleId }
        manager = mine
        mine?.let { observe(it) }
        return mine
    }

    override suspend fun install(profileId: String, tunnel: TunnelConfiguration, localizedDescription: String) {
        val m = load() ?: NETunnelProviderManager()
        val proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?: NETunnelProviderProtocol()
        proto.providerBundleIdentifier = ids.tunnelBundleId
        proto.serverAddress = tunnel.tunnelRemoteAddress // shown by iOS Settings; never the proxy secret
        proto.providerConfiguration = ProviderConfigKeys.build(profileId, tunnel).mapKeys { it.key as Any? }.mapValues { it.value as Any? }
        proto.disconnectOnSleep = false
        m.protocolConfiguration = proto
        m.localizedDescription = localizedDescription
        m.enabled = true
        suspendCancellableCoroutine<Unit> { cont ->
            m.saveToPreferencesWithCompletionHandler { error ->
                if (error != null) cont.resumeWithException(VpnError.PermissionOrConfigurationUnavailable("saveToPreferences ${error.code}"))
                else cont.resume(Unit)
            }
        }
        // Apple requires a reload after save before the manager can start a tunnel.
        load() ?: throw VpnError.PermissionOrConfigurationUnavailable("configuration not present after save")
    }

    override suspend fun uninstall() {
        val m = manager ?: load() ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            m.removeFromPreferencesWithCompletionHandler { error ->
                if (error != null) cont.resumeWithException(VpnError.PermissionOrConfigurationUnavailable("removeFromPreferences ${error.code}"))
                else cont.resume(Unit)
            }
        }
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }; observer = null
        manager = null; _status.value = VpnStatus.INVALID
    }

    override suspend fun start(profileId: String) {
        val m = manager ?: load() ?: throw VpnError.PermissionOrConfigurationUnavailable("no saved VPN configuration")
        if (!m.enabled) throw VpnError.PermissionOrConfigurationUnavailable("configuration disabled in Settings")
        val session = m.connection as? NETunnelProviderSession ?: throw VpnError.ProviderUnavailable("no provider session")
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = session.startVPNTunnelWithOptions(mapOf<Any?, Any?>(ProviderConfigKeys.PROFILE_ID to profileId), err.ptr)
            if (!ok) throw VpnError.ProviderStartupFailure("startVPNTunnel ${err.value?.code ?: -1}")
        }
    }

    override suspend fun stop() {
        val m = manager ?: load() ?: return
        m.connection.stopVPNTunnel()
    }

    override val ipc: IpcClient?
        get() {
            val session = manager?.connection as? NETunnelProviderSession ?: return null
            if (_status.value == VpnStatus.INVALID || _status.value == VpnStatus.DISCONNECTED) return null
            return IpcClient(SessionTransport(session), ipcTimeoutMs)
        }

    private class SessionTransport(private val session: NETunnelProviderSession) : IpcTransport {
        override suspend fun send(bytes: ByteArray): ByteArray? = suspendCancellableCoroutine { cont ->
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val accepted = session.sendProviderMessage(bytes.toNSData(), err.ptr) { reply -> cont.resume(reply?.toByteArray()) }
                if (!accepted) cont.resumeWithException(VpnError.ProviderUnavailable("sendProviderMessage rejected ${err.value?.code ?: -1}"))
            }
        }
    }
}

internal fun NEVPNStatus.toVpnStatus(): VpnStatus = when (this.toInt()) {
    1 -> VpnStatus.DISCONNECTED
    2 -> VpnStatus.CONNECTING
    3 -> VpnStatus.CONNECTED
    4 -> VpnStatus.REASSERTING
    5 -> VpnStatus.DISCONNECTING
    else -> VpnStatus.INVALID
}
