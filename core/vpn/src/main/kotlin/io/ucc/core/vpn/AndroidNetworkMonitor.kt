package io.ucc.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.ucc.core.engine.manager.NetworkEvent
import io.ucc.core.engine.manager.NetworkMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tracks the *underlying* default network (never our own TUN) using a request
 * that excludes VPN transports. Provides both the reactive [events] used by the
 * ConnectionManager and the synchronous [current] network used by the DNS
 * transport and the interface bridge.
 */
public class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _current = MutableStateFlow<Network?>(null)
    /** The current underlying network, or null when none. */
    public val current: StateFlow<Network?> = _current

    /** Interface details of the current network for the core's interface monitor. */
    public var onInterface: ((name: String, index: Int, expensive: Boolean) -> Unit)? = null
    public var onInterfaceLost: (() -> Unit)? = null

    override val events: Flow<NetworkEvent> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastKey: String? = null

            override fun onAvailable(network: Network) {
                publish(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == _current.value) publish(network, caps)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network == _current.value) publish(network, link = lp)
            }

            override fun onLost(network: Network) {
                if (network == _current.value) {
                    _current.value = null
                    lastKey = null
                    onInterfaceLost?.invoke()
                    trySend(NetworkEvent.Lost)
                }
            }

            private fun publish(network: Network, caps: NetworkCapabilities? = null, link: LinkProperties? = null) {
                val c = caps ?: cm.getNetworkCapabilities(network) ?: return
                val l = link ?: cm.getLinkProperties(network) ?: return
                val transport = transportName(c)
                val ifName = l.interfaceName ?: return
                val key = "$transport:$ifName:${network.hashCode()}"
                _current.value = network
                val index = runCatching { java.net.NetworkInterface.getByName(ifName)?.index ?: -1 }.getOrDefault(-1)
                val expensive = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                onInterface?.invoke(ifName, index, expensive)
                if (key != lastKey) {
                    lastKey = key
                    trySend(NetworkEvent.DefaultChanged(key, transport))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cm.registerBestMatchingNetworkCallback(request, callback, android.os.Handler(android.os.Looper.getMainLooper()))
        } else {
            cm.registerDefaultNetworkCallback(callback)
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun transportName(c: NetworkCapabilities): String = when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }
}
