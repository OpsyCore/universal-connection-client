package io.ucc.app.data

import io.ucc.app.data.ConnectionSettings.PerAppMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionSettingsTest {
    @Test fun `dns spec grammar`() {
        listOf("local", "1.1.1.1", "udp://8.8.8.8", "udp://8.8.8.8:53", "tls://dns.quad9.net", "https://dns.google/dns-query", "h3://1.1.1.1:443/dns-query", "quic://[2606:4700::1111]:853")
            .forEach { assertTrue(ConnectionSettings.isValidDnsSpec(it), it) }
        listOf("", "   ", "ftp://x", "udp://", "udp://1.1.1.1/path", "https://", "https://dns.google:99999/x", "not a host!", "tls://a b")
            .forEach { assertFalse(ConnectionSettings.isValidDnsSpec(it), it) }
    }

    @Test fun `validate reports each problem`() {
        assertTrue(ConnectionSettings().validate().isEmpty())
        val bad = ConnectionSettings(remoteDns = "local", directDns = "nope!", mtu = 100).validate()
        assertEquals(setOf(ConnectionSettings.Problem.RemoteDnsInvalid, ConnectionSettings.Problem.DirectDnsInvalid, ConnectionSettings.Problem.MtuOutOfRange), bad.toSet())
    }

    @Test fun `toStartOptions maps fields and drops per-app when the core lacks it`() {
        val s = ConnectionSettings(remoteDns = "tls://9.9.9.9", directDns = "udp://192.168.1.1", bypassPrivate = false, ipv6 = false, strictRoute = false, mtu = 1400,
            perAppMode = PerAppMode.EXCLUDE, perAppPackages = setOf("b.app", "a.app"), logLevel = ConnectionSettings.LogLevel.DEBUG)
        val o = s.toStartOptions(testCapabilities)
        assertEquals("tls://9.9.9.9", o.remoteDns); assertEquals("udp://192.168.1.1", o.directDns)
        assertFalse(o.bypassPrivate); assertFalse(o.ipv6); assertFalse(o.strictRoute); assertEquals(1400, o.mtu); assertEquals("debug", o.logLevel)
        assertEquals(listOf("a.app", "b.app"), o.excludePackages); assertTrue(o.includePackages.isEmpty())
        val noPerApp = s.toStartOptions(testCapabilities.copy(perAppRouting = false))
        assertTrue(noPerApp.excludePackages.isEmpty())
        val include = s.copy(perAppMode = PerAppMode.INCLUDE).toStartOptions(testCapabilities)
        assertEquals(listOf("a.app", "b.app"), include.includePackages); assertTrue(include.excludePackages.isEmpty())
    }

    @Test fun `invalid values never reach the core`() {
        val o = ConnectionSettings(remoteDns = "garbage!", directDns = "also bad", mtu = 50).toStartOptions(testCapabilities)
        assertNull(o.remoteDns); assertNull(o.directDns); assertEquals(1280, o.mtu)
    }

    @Test fun `settings survive json round trip with unknown keys ignored`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val s = ConnectionSettings(perAppMode = PerAppMode.INCLUDE, perAppPackages = setOf("x"))
        val text = json.encodeToString(ConnectionSettings.serializer(), s).replace("}", ",\"futureKey\":1}")
        assertEquals(s, json.decodeFromString(ConnectionSettings.serializer(), text))
    }

    @Test fun `rule items are classified and mapped into typed routing rules`() {
        val r = ConnectionSettings.Rule("r1", io.ucc.core.engine.RouteAction.DIRECT,
            listOf("example.com", "*.ir", ".gov.ir", "keyword:google", "10.0.0.0/8", "8.8.8.8", "2001:db8::/32", "bad host!", "999.1.1.1"))
        val t = r.toRoutingRule()
        assertEquals(listOf("example.com"), t.domains)
        assertEquals(listOf(".ir", ".gov.ir"), t.domainSuffixes)
        assertEquals(listOf("google"), t.domainKeywords)
        assertEquals(listOf("10.0.0.0/8", "8.8.8.8/32", "2001:db8::/32"), t.ipCidrs)
        assertEquals(ConnectionSettings.Rule.Item.Invalid, ConnectionSettings.Rule.classify("bad host!"))
        assertEquals(ConnectionSettings.Rule.Item.Invalid, ConnectionSettings.Rule.classify("999.1.1.1"))
    }

    @Test fun `disabled and empty rules never reach the core, order preserved`() {
        val s = ConnectionSettings(rules = listOf(
            ConnectionSettings.Rule("a", io.ucc.core.engine.RouteAction.BLOCK, listOf("ads.example")),
            ConnectionSettings.Rule("b", io.ucc.core.engine.RouteAction.DIRECT, listOf("x.ir"), enabled = false),
            ConnectionSettings.Rule("c", io.ucc.core.engine.RouteAction.PROXY, listOf("!!!")),
            ConnectionSettings.Rule("d", io.ucc.core.engine.RouteAction.DIRECT, listOf("1.1.1.1")),
        ))
        val o = s.toStartOptions(testCapabilities)
        assertEquals(listOf(io.ucc.core.engine.RouteAction.BLOCK, io.ucc.core.engine.RouteAction.DIRECT), o.rules.map { it.action })
        assertEquals(listOf("1.1.1.1/32"), o.rules[1].ipCidrs)
    }

    @Test fun `invalid combinations are reported`() {
        val p = ConnectionSettings(remoteDns = "192.168.1.1", directDns = "192.168.1.1",
            rules = listOf(ConnectionSettings.Rule("e", io.ucc.core.engine.RouteAction.DIRECT, emptyList()), ConnectionSettings.Rule("f", io.ucc.core.engine.RouteAction.DIRECT, listOf("ok.com", "no good")))).validate()
        assertTrue(ConnectionSettings.Problem.RemoteDnsPrivate in p)
        assertTrue(ConnectionSettings.Problem.RemoteEqualsDirect in p)
        assertTrue(ConnectionSettings.Problem.RuleEmpty("e") in p)
        assertTrue(ConnectionSettings.Problem.RuleItemInvalid("f", "no good") in p)
        assertFalse(ConnectionSettings.isPrivateUdpDns("https://192.168.1.1/dns-query"), "DoH to LAN is unusual but reachable via detour rules; only plain udp/tcp is flagged")
        assertTrue(ConnectionSettings(remoteDns = "tls://9.9.9.9", directDns = "tls://9.9.9.9").blockingProblems.isEmpty())
    }

    @Test fun `rules serialize and survive round trip`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val s = ConnectionSettings(rules = listOf(ConnectionSettings.Rule("r", io.ucc.core.engine.RouteAction.BLOCK, listOf("a.com", "10.0.0.0/8"), enabled = false)))
        val text = json.encodeToString(ConnectionSettings.serializer(), s)
        assertTrue("\"BLOCK\"" in text)
        assertEquals(s, json.decodeFromString(ConnectionSettings.serializer(), text))
    }
}
