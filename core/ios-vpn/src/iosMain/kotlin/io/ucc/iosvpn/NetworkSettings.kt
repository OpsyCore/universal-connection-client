@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.iosvpn

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import platform.NetworkExtension.NEDNSSettings
import platform.NetworkExtension.NEIPv4Route
import platform.NetworkExtension.NEIPv4Settings
import platform.NetworkExtension.NEIPv6Route
import platform.NetworkExtension.NEIPv6Settings
import platform.NetworkExtension.NEPacketTunnelNetworkSettings
import platform.NetworkExtension.NEProxyServer
import platform.NetworkExtension.NEProxySettings

/** Pure copy of the already-validated [NetworkSettingsSpec] into Apple objects. */
internal fun NetworkSettingsSpec.toNetworkSettings(): NEPacketTunnelNetworkSettings {
    val s = NEPacketTunnelNetworkSettings(tunnelRemoteAddress = tunnelRemoteAddress)
    s.MTU = NSNumber(int = mtu)
    ipv4?.let { v4 ->
        val ip = NEIPv4Settings(addresses = v4.addresses, subnetMasks = v4.subnetMasks)
        ip.includedRoutes = buildList<NEIPv4Route> {
            if (v4.includeDefaultRoute) add(NEIPv4Route.defaultRoute())
            v4.includedRoutes.forEach { add(NEIPv4Route(destinationAddress = it.destination, subnetMask = it.subnetMask)) }
        }
        ip.excludedRoutes = v4.excludedRoutes.map { NEIPv4Route(destinationAddress = it.destination, subnetMask = it.subnetMask) }
        s.IPv4Settings = ip
    }
    ipv6?.let { v6 ->
        val ip = NEIPv6Settings(addresses = v6.addresses, networkPrefixLengths = v6.networkPrefixLengths.map { NSNumber(int = it) })
        ip.includedRoutes = buildList<NEIPv6Route> {
            if (v6.includeDefaultRoute) add(NEIPv6Route.defaultRoute())
            v6.includedRoutes.forEach { add(NEIPv6Route(destinationAddress = it.destination, networkPrefixLength = NSNumber(int = it.prefixLength))) }
        }
        ip.excludedRoutes = v6.excludedRoutes.map { NEIPv6Route(destinationAddress = it.destination, networkPrefixLength = NSNumber(int = it.prefixLength)) }
        s.IPv6Settings = ip
    }
    if (dnsServers.isNotEmpty()) {
        val dns = NEDNSSettings(servers = dnsServers)
        dns.matchDomains = listOf("") // route all DNS through the tunnel resolver
        s.DNSSettings = dns
    }
    proxy?.let { p ->
        val ps = NEProxySettings()
        val server = NEProxyServer(address = p.host, port = p.port.toLong())
        ps.HTTPEnabled = true; ps.HTTPServer = server
        ps.HTTPSEnabled = true; ps.HTTPSServer = server
        ps.excludeSimpleHostnames = true
        ps.exceptionList = p.bypassDomains
        s.proxySettings = ps
    }
    return s
}
