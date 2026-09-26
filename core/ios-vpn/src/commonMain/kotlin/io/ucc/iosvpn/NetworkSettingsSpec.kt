package io.ucc.iosvpn

/**
 * Framework-free description of exactly what will be pushed into
 * `NEPacketTunnelNetworkSettings`. Computed and unit-tested in common code; the
 * iOS actual only copies these fields into the Apple objects.
 */
public data class NetworkSettingsSpec(
    val tunnelRemoteAddress: String,
    val mtu: Int,
    val ipv4: V4?,
    val ipv6: V6?,
    val dnsServers: List<String>,
    val proxy: TunnelConfiguration.Proxy?,
) {
    public data class V4Route(val destination: String, val subnetMask: String)
    public data class V6Route(val destination: String, val prefixLength: Int)

    public data class V4(
        val addresses: List<String>,
        val subnetMasks: List<String>,
        /** null entry list means "default route" (NEIPv4Route.defaultRoute). */
        val includedRoutes: List<V4Route>,
        val includeDefaultRoute: Boolean,
        val excludedRoutes: List<V4Route>,
    )

    public data class V6(
        val addresses: List<String>,
        val networkPrefixLengths: List<Int>,
        val includedRoutes: List<V6Route>,
        val includeDefaultRoute: Boolean,
        val excludedRoutes: List<V6Route>,
    )

    public companion object {
        public fun from(config: TunnelConfiguration): NetworkSettingsSpec {
            val c = config.validated()
            val v4 = if (c.inet4Addresses.isEmpty()) null else V4(
                addresses = c.inet4Addresses.map { it.address },
                subnetMasks = c.inet4Addresses.map { Ip.v4Mask(it.prefixLength) },
                includedRoutes = c.inet4Routes.map { V4Route(it.address, Ip.v4Mask(it.prefixLength)) },
                includeDefaultRoute = c.autoRoute && c.inet4Routes.isEmpty(),
                excludedRoutes = c.inet4ExcludedRoutes.map { V4Route(it.address, Ip.v4Mask(it.prefixLength)) },
            )
            val v6 = if (c.inet6Addresses.isEmpty()) null else V6(
                addresses = c.inet6Addresses.map { it.address },
                networkPrefixLengths = c.inet6Addresses.map { it.prefixLength },
                includedRoutes = c.inet6Routes.map { V6Route(it.address, it.prefixLength) },
                includeDefaultRoute = c.autoRoute && c.inet6Routes.isEmpty(),
                excludedRoutes = c.inet6ExcludedRoutes.map { V6Route(it.address, it.prefixLength) },
            )
            return NetworkSettingsSpec(c.tunnelRemoteAddress, c.mtu, v4, v6, c.dnsServers, c.httpProxy)
        }
    }
}
