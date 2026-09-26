package io.ucc.iosvpn

import io.ucc.core.engine.IpPrefix
import io.ucc.core.engine.TunRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sampleConfig() = TunnelConfiguration(
    tunnelRemoteAddress = "127.0.0.1", mtu = 9000,
    inet4Addresses = listOf(TunnelConfiguration.Prefix("172.19.0.1", 30)),
    inet6Addresses = listOf(TunnelConfiguration.Prefix("fdfe:dcba:9876::1", 126)),
    dnsServers = listOf("172.19.0.2"),
    inet4ExcludedRoutes = listOf(TunnelConfiguration.Prefix("10.0.0.0", 8)),
)

class TunnelConfigurationTest {
    @Test fun json_round_trip_and_tolerance() {
        val c = sampleConfig()
        val text = c.encode()
        assertEquals(c, TunnelConfiguration.decode(text))
        assertEquals(c, TunnelConfiguration.decode(text.dropLast(1) + ",\"future\":1}"))
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { TunnelConfiguration.decode("{nope") }
    }

    @Test fun tun_request_round_trip_drops_packages() {
        val req = TunRequest(1500, listOf(IpPrefix("172.19.0.1", 30)), emptyList(), listOf("1.1.1.1"), emptyList(), emptyList(),
            listOf(IpPrefix("192.168.0.0", 16)), emptyList(), listOf("com.x"), listOf("com.y"), true, null)
        val cfg = TunnelConfiguration.from(req)
        val back = cfg.toTunRequest()
        assertEquals(req.copy(includePackages = emptyList(), excludePackages = emptyList()), back)
        assertEquals(TunnelConfiguration.DEFAULT_REMOTE_ADDRESS, cfg.tunnelRemoteAddress)
    }

    @Test fun validation_rejects_bad_values() {
        fun bad(c: TunnelConfiguration) = assertFailsWith<VpnError.InvalidTunnelConfiguration> { c.validated() }
        bad(sampleConfig().copy(tunnelRemoteAddress = " "))
        bad(sampleConfig().copy(mtu = 100)); bad(sampleConfig().copy(mtu = 70000))
        bad(sampleConfig().copy(inet4Addresses = emptyList(), inet6Addresses = emptyList()))
        bad(sampleConfig().copy(inet4Addresses = listOf(TunnelConfiguration.Prefix("999.1.1.1", 24))))
        bad(sampleConfig().copy(inet4Addresses = listOf(TunnelConfiguration.Prefix("10.0.0.1", 33))))
        bad(sampleConfig().copy(inet6Addresses = listOf(TunnelConfiguration.Prefix("fd00::1", 129))))
        bad(sampleConfig().copy(inet6Addresses = listOf(TunnelConfiguration.Prefix("not-v6", 64))))
        bad(sampleConfig().copy(dnsServers = listOf("dns.example")))
        bad(sampleConfig().copy(httpProxy = TunnelConfiguration.Proxy("", 8080)))
        bad(sampleConfig().copy(httpProxy = TunnelConfiguration.Proxy("127.0.0.1", 0)))
        assertEquals(sampleConfig(), sampleConfig().validated())
    }

    @Test fun errors_do_not_leak_values() {
        val e = assertFailsWith<VpnError.InvalidTunnelConfiguration> { sampleConfig().copy(dnsServers = listOf("secret.host")).validated() }
        assertFalse(e.detail.contains("secret.host"))
    }

    @Test fun ip_literals() {
        assertTrue(Ip.isV4("0.0.0.0")); assertTrue(Ip.isV4("255.255.255.255")); assertFalse(Ip.isV4("256.1.1.1")); assertFalse(Ip.isV4("1.1.1")); assertFalse(Ip.isV4("a.b.c.d"))
        assertTrue(Ip.isV6("::")); assertTrue(Ip.isV6("fd00::1")); assertTrue(Ip.isV6("2001:db8:0:0:0:0:0:1")); assertFalse(Ip.isV6("fd00:::1")); assertFalse(Ip.isV6("1::2::3")); assertFalse(Ip.isV6("2001:db8:0:0:0:0:0:1:2"))
        assertEquals("255.255.255.0", Ip.v4Mask(24)); assertEquals("0.0.0.0", Ip.v4Mask(0)); assertEquals("255.255.255.255", Ip.v4Mask(32)); assertEquals("255.255.255.252", Ip.v4Mask(30))
    }
}

class NetworkSettingsSpecTest {
    @Test fun maps_addresses_masks_routes_dns() {
        val s = NetworkSettingsSpec.from(sampleConfig())
        assertEquals("127.0.0.1", s.tunnelRemoteAddress); assertEquals(9000, s.mtu)
        val v4 = s.ipv4!!
        assertEquals(listOf("172.19.0.1"), v4.addresses); assertEquals(listOf("255.255.255.252"), v4.subnetMasks)
        assertTrue(v4.includeDefaultRoute); assertTrue(v4.includedRoutes.isEmpty())
        assertEquals(listOf(NetworkSettingsSpec.V4Route("10.0.0.0", "255.0.0.0")), v4.excludedRoutes)
        val v6 = s.ipv6!!
        assertEquals(listOf(126), v6.networkPrefixLengths); assertTrue(v6.includeDefaultRoute)
        assertEquals(listOf("172.19.0.2"), s.dnsServers); assertNull(s.proxy)
    }

    @Test fun explicit_routes_disable_default_route() {
        val s = NetworkSettingsSpec.from(sampleConfig().copy(inet4Routes = listOf(TunnelConfiguration.Prefix("0.0.0.0", 1), TunnelConfiguration.Prefix("128.0.0.0", 1))))
        assertFalse(s.ipv4!!.includeDefaultRoute); assertEquals(2, s.ipv4!!.includedRoutes.size)
        assertTrue(s.ipv6!!.includeDefaultRoute)
    }

    @Test fun auto_route_off_means_no_default_route() {
        val s = NetworkSettingsSpec.from(sampleConfig().copy(autoRoute = false))
        assertFalse(s.ipv4!!.includeDefaultRoute); assertFalse(s.ipv6!!.includeDefaultRoute)
    }

    @Test fun v4_only_and_v6_only() {
        assertNull(NetworkSettingsSpec.from(sampleConfig().copy(inet6Addresses = emptyList())).ipv6)
        assertNull(NetworkSettingsSpec.from(sampleConfig().copy(inet4Addresses = emptyList(), dnsServers = emptyList())).ipv4)
    }

    @Test fun invalid_config_fails_mapping() {
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { NetworkSettingsSpec.from(sampleConfig().copy(mtu = 1)) }
    }
}
