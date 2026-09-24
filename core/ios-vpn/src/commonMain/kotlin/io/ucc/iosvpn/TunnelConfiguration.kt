package io.ucc.iosvpn

import io.ucc.core.engine.HttpProxySpec
import io.ucc.core.engine.IpPrefix
import io.ucc.core.engine.TunRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed tunnel interface configuration for the packet tunnel. It is the Apple-side
 * twin of [TunRequest] (the value the core hands to `TunProvider.openTun`) with
 * validation, and it maps 1:1 onto `NEPacketTunnelNetworkSettings` via
 * [NetworkSettingsSpec]. Per-app package lists have no iOS equivalent and are dropped.
 */
@Serializable
public data class TunnelConfiguration(
    /** Address reported as `tunnelRemoteAddress`; a routing anchor, never the proxy secret. */
    val tunnelRemoteAddress: String,
    val mtu: Int,
    val inet4Addresses: List<Prefix> = emptyList(),
    val inet6Addresses: List<Prefix> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val inet4Routes: List<Prefix> = emptyList(),
    val inet6Routes: List<Prefix> = emptyList(),
    val inet4ExcludedRoutes: List<Prefix> = emptyList(),
    val inet6ExcludedRoutes: List<Prefix> = emptyList(),
    /** true = route everything (default routes) when the explicit route lists are empty. */
    val autoRoute: Boolean = true,
    val httpProxy: Proxy? = null,
) {
    @Serializable
    public data class Prefix(val address: String, val prefixLength: Int) {
        public fun toIpPrefix(): IpPrefix = IpPrefix(address, prefixLength)
        public companion object { public fun of(p: IpPrefix): Prefix = Prefix(p.address, p.prefixLength) }
    }

    @Serializable
    public data class Proxy(val host: String, val port: Int, val bypassDomains: List<String> = emptyList())

    /** @throws VpnError.InvalidTunnelConfiguration */
    public fun validated(): TunnelConfiguration {
        fun bad(msg: String): Nothing = throw VpnError.InvalidTunnelConfiguration(msg)
        if (tunnelRemoteAddress.isBlank()) bad("tunnelRemoteAddress missing")
        if (mtu !in MIN_MTU..MAX_MTU) bad("mtu $mtu out of range $MIN_MTU..$MAX_MTU")
        if (inet4Addresses.isEmpty() && inet6Addresses.isEmpty()) bad("no tunnel address")
        for (p in inet4Addresses + inet4Routes + inet4ExcludedRoutes) {
            if (!Ip.isV4(p.address)) bad("not an IPv4 address")
            if (p.prefixLength !in 0..32) bad("ipv4 prefix ${p.prefixLength} out of range")
        }
        for (p in inet6Addresses + inet6Routes + inet6ExcludedRoutes) {
            if (!Ip.isV6(p.address)) bad("not an IPv6 address")
            if (p.prefixLength !in 0..128) bad("ipv6 prefix ${p.prefixLength} out of range")
        }
        for (d in dnsServers) if (!Ip.isV4(d) && !Ip.isV6(d)) bad("dns server is not an IP literal")
        httpProxy?.let { if (it.host.isBlank() || it.port !in 1..65535) bad("invalid proxy endpoint") }
        return this
    }

    public fun toTunRequest(): TunRequest = TunRequest(
        mtu = mtu,
        inet4Addresses = inet4Addresses.map { it.toIpPrefix() },
        inet6Addresses = inet6Addresses.map { it.toIpPrefix() },
        dnsServers = dnsServers,
        inet4Routes = inet4Routes.map { it.toIpPrefix() },
        inet6Routes = inet6Routes.map { it.toIpPrefix() },
        inet4ExcludedRoutes = inet4ExcludedRoutes.map { it.toIpPrefix() },
        inet6ExcludedRoutes = inet6ExcludedRoutes.map { it.toIpPrefix() },
        includePackages = emptyList(),
        excludePackages = emptyList(),
        autoRoute = autoRoute,
        httpProxy = httpProxy?.let { HttpProxySpec(it.host, it.port, it.bypassDomains) },
    )

    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        public const val MIN_MTU: Int = 576
        public const val MAX_MTU: Int = 65535
        /** Apple requires a remote address even for a local tun; sing-box's own Apple client uses this anchor. */
        public const val DEFAULT_REMOTE_ADDRESS: String = "127.0.0.1"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        public fun from(request: TunRequest, tunnelRemoteAddress: String = DEFAULT_REMOTE_ADDRESS): TunnelConfiguration = TunnelConfiguration(
            tunnelRemoteAddress = tunnelRemoteAddress,
            mtu = request.mtu,
            inet4Addresses = request.inet4Addresses.map(Prefix::of),
            inet6Addresses = request.inet6Addresses.map(Prefix::of),
            dnsServers = request.dnsServers,
            inet4Routes = request.inet4Routes.map(Prefix::of),
            inet6Routes = request.inet6Routes.map(Prefix::of),
            inet4ExcludedRoutes = request.inet4ExcludedRoutes.map(Prefix::of),
            inet6ExcludedRoutes = request.inet6ExcludedRoutes.map(Prefix::of),
            autoRoute = request.autoRoute,
            httpProxy = request.httpProxy?.let { Proxy(it.host, it.port, it.bypassDomains) },
        )

        /** @throws VpnError.InvalidTunnelConfiguration on malformed JSON */
        public fun decode(text: String): TunnelConfiguration =
            runCatching { json.decodeFromString(serializer(), text) }
                .getOrElse { throw VpnError.InvalidTunnelConfiguration("undecodable tunnel configuration") }
    }
}

/** Minimal literal checks (no DNS, no allocation-heavy parsing) shared by validation and settings mapping. */
public object Ip {
    public fun isV4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    public fun isV6(s: String): Boolean {
        if (s.isEmpty() || s.count { it == ':' } < 2) return false
        if (s.contains(":::")) return false
        val doubleColon = s.indexOf("::")
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) return false
        val groups = s.split(':').filter { it.isNotEmpty() }
        if (doubleColon < 0 && groups.size != 8) return false
        if (doubleColon >= 0 && groups.size > 7) return false
        return groups.all { g -> g.length in 1..4 && g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
    }

    /** "/24" -> "255.255.255.0" */
    public fun v4Mask(prefixLength: Int): String {
        require(prefixLength in 0..32)
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        return "${(mask shr 24) and 0xff}.${(mask shr 16) and 0xff}.${(mask shr 8) and 0xff}.${mask and 0xff}"
    }
}
