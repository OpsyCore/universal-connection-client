package io.ucc.core.singbox.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.ucc.core.engine.HttpProxySpec
import io.ucc.core.engine.IpPrefix
import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.TunRequest
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Base64
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * sing-box ↔ Android glue. The core calls back into this object to open the
 * TUN, protect sockets, enumerate interfaces, resolve DNS on the underlying
 * network, and learn about default-network changes.
 *
 * [tunProvider] is swapped per connection by [SingBoxCoreAdapter].
 */
internal class AndroidPlatformInterface(
    private val context: Context,
    private val defaultNetwork: () -> Network?,
    private val interfaceMonitor: DefaultInterfaceBridge,
) : PlatformInterface {

    @Volatile var tunProvider: TunProvider? = null

    private val connectivity: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val localDns = LocalDnsTransport(defaultNetwork)

    override fun localDNSTransport(): LocalDNSTransport = localDns

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        val provider = tunProvider ?: error("android: no active tunnel to protect socket")
        if (!provider.protectSocket(fd)) error("android: VpnService.protect($fd) failed")
    }

    override fun openTun(options: TunOptions): Int {
        val provider = tunProvider ?: error("android: openTun called without a tunnel host")
        return provider.openTun(options.toRequest())
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("android: connection owner lookup requires API 29")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().also {
            it.userId = uid
            it.userName = packages.firstOrNull() ?: ""
            it.setAndroidPackageNames(packages.toStringIterator())
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = connectivity
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = ArrayList<LibboxNetworkInterface>()
        for (network in cm.allNetworks) {
            val link = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val name = link.interfaceName ?: continue
            val javaIf = javaInterfaces.firstOrNull { it.name == name } ?: continue
            val bi = LibboxNetworkInterface()
            bi.name = name
            bi.index = javaIf.index
            bi.mtu = runCatching { javaIf.mtu }.getOrDefault(0)
            bi.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            bi.dnsServer = link.dnsServers.mapNotNull { it.hostAddress }.toStringIterator()
            bi.addresses = javaIf.interfaceAddresses.map { it.toPrefix() }.toStringIterator()
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (javaIf.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (javaIf.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (javaIf.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            bi.flags = flags
            bi.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result += bi
        }
        return object : NetworkInterfaceIterator {
            private var i = 0
            override fun hasNext(): Boolean = i < result.size
            override fun next(): LibboxNetworkInterface = result[i++]
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    /** Wi-Fi SSID rules need location permission; not supported in v1 — return empty state honestly. */
    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator {
        val pems = ArrayList<String>()
        runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            val aliases = ks.aliases()
            val enc = Base64.getMimeEncoder(64, "\n".toByteArray())
            while (aliases.hasMoreElements()) {
                val cert = ks.getCertificate(aliases.nextElement()) ?: continue
                pems += "-----BEGIN CERTIFICATE-----\n" + enc.encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"
            }
        }
        return pems.toStringIterator()
    }

    override fun clearDNSCache() = Unit

    /** Core-originated notifications are routed through our own notification channel by the service (Phase 3). */
    override fun sendNotification(notification: Notification) {
        notificationSink?.invoke(notification)
    }

    @Volatile var notificationSink: ((Notification) -> Unit)? = null

    // ---------------------------------------------------------------- helpers

    private fun TunOptions.toRequest(): TunRequest = TunRequest(
        mtu = mtu,
        inet4Addresses = inet4Address.toPrefixes(),
        inet6Addresses = inet6Address.toPrefixes(),
        dnsServers = runCatching { listOf(dnsServerAddress.value) }.getOrDefault(emptyList()),
        inet4Routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) inet4RouteAddress.toPrefixes() else inet4RouteRange.toPrefixes(),
        inet6Routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) inet6RouteAddress.toPrefixes() else inet6RouteRange.toPrefixes(),
        inet4ExcludedRoutes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) inet4RouteExcludeAddress.toPrefixes() else emptyList(),
        inet6ExcludedRoutes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) inet6RouteExcludeAddress.toPrefixes() else emptyList(),
        includePackages = includePackage.toList(),
        excludePackages = excludePackage.toList(),
        autoRoute = autoRoute,
        httpProxy = if (isHTTPProxyEnabled) HttpProxySpec(httpProxyServer, httpProxyServerPort, httpProxyBypassDomain.toList()) else null,
    )

    private fun io.nekohasekai.libbox.RoutePrefixIterator.toPrefixes(): List<IpPrefix> {
        val out = ArrayList<IpPrefix>()
        while (hasNext()) {
            val p = next()
            out += IpPrefix(p.address(), p.prefix())
        }
        return out
    }

    private fun InterfaceAddress.toPrefix(): String {
        val host = address.hostAddress ?: ""
        val stripped = host.substringBefore('%')
        return "$stripped/${networkPrefixLength}"
    }
}

/** Receives default-network updates from the VPN layer and forwards them to the core. */
internal class DefaultInterfaceBridge {
    @Volatile private var listener: InterfaceUpdateListener? = null
    @Volatile private var last: Triple<String, Int, Boolean>? = null

    fun setListener(l: InterfaceUpdateListener?) {
        listener = l
        // Replay the current network so the core starts with a valid default interface.
        val cached = last
        if (l != null && cached != null) l.updateDefaultInterface(cached.first, cached.second, cached.third, false)
    }

    fun update(interfaceName: String, index: Int, isExpensive: Boolean) {
        last = Triple(interfaceName, index, isExpensive)
        listener?.updateDefaultInterface(interfaceName, index, isExpensive, false)
    }

    fun lost() {
        last = null
        listener?.updateDefaultInterface("", -1, false, false)
    }
}
