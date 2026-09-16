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
}
