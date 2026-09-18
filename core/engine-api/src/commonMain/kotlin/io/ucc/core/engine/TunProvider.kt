package io.ucc.core.engine

/**
 * Bridge from the core to the platform's TUN facility. On Android this is
 * implemented by the VpnService; the core calls [openTun] when it needs a
 * descriptor and [protectSocket] for every upstream socket.
 */
public interface TunProvider {
    /** Establishes a TUN with the given parameters and returns its file descriptor. */
    public fun openTun(request: TunRequest): Int

    /** Excludes [fd] from the tunnel so upstream traffic does not loop. Returns false on failure. */
    public fun protectSocket(fd: Int): Boolean
}

public data class TunRequest(
    val mtu: Int,
    val inet4Addresses: List<IpPrefix>,
    val inet6Addresses: List<IpPrefix>,
    val dnsServers: List<String>,
    val inet4Routes: List<IpPrefix>,
    val inet6Routes: List<IpPrefix>,
    val inet4ExcludedRoutes: List<IpPrefix>,
    val inet6ExcludedRoutes: List<IpPrefix>,
    val includePackages: List<String>,
    val excludePackages: List<String>,
    val autoRoute: Boolean,
    val httpProxy: HttpProxySpec?,
)

public data class IpPrefix(val address: String, val prefixLength: Int)

public data class HttpProxySpec(val host: String, val port: Int, val bypassDomains: List<String>)
