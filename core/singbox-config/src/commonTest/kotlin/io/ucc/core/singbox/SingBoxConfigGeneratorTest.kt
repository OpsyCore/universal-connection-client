package io.ucc.core.singbox

import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.RouteAction
import io.ucc.core.engine.RoutingRule
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ucc.core.model.toLogString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingBoxConfigGeneratorTest {
    private val gen = SingBoxConfigGenerator()

    private fun base(protocol: Protocol, auth: Authentication, tls: TlsSettings = TlsSettings(), transport: Transport = Transport.None) =
        ConnectionProfile(id = "id", name = "n", protocol = protocol, address = "srv.example.com", port = 443, authentication = auth, tls = tls, transport = transport)

    @Test
    fun `vless reality vision produces expected outbound`() {
        val p = base(
            Protocol.VLESS,
            Authentication.Vless(uuid = "11111111-2222-3333-4444-555555555555", flow = "xtls-rprx-vision"),
            TlsSettings(enabled = true, serverName = "www.microsoft.com", fingerprint = "chrome", reality = TlsSettings.Reality("pubkey", "ab12")),
        )
        val ob = gen.buildOutbound(p)
        assertEquals("vless", ob["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", ob["tag"]!!.jsonPrimitive.content)
        assertEquals("srv.example.com", ob["server"]!!.jsonPrimitive.content)
        assertEquals(443, ob["server_port"]!!.jsonPrimitive.int)
        assertEquals("xtls-rprx-vision", ob["flow"]!!.jsonPrimitive.content)
        val tls = ob["tls"]!!.jsonObject
        assertTrue(tls["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("www.microsoft.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        val reality = tls["reality"]!!.jsonObject
        assertEquals("pubkey", reality["public_key"]!!.jsonPrimitive.content)
        assertEquals("ab12", reality["short_id"]!!.jsonPrimitive.content)
        assertNull(ob["transport"])
    }

    @Test
    fun `vmess websocket with host header`() {
        val p = base(
            Protocol.VMESS,
            Authentication.Vmess(uuid = "u", alterId = 0, security = "auto"),
            TlsSettings(enabled = true, serverName = "cdn.example.com"),
            Transport.WebSocket(path = "/ws", host = "cdn.example.com"),
        )
        val ob = gen.buildOutbound(p)
        val tr = ob["transport"]!!.jsonObject
        assertEquals("ws", tr["type"]!!.jsonPrimitive.content)
        assertEquals("/ws", tr["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", tr["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertEquals(0, ob["alter_id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `trojan forces tls even when link omitted security`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"))
        val ob = gen.buildOutbound(p)
        assertTrue(ob["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("pw", ob["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `shadowsocks with plugin`() {
        val p = base(Protocol.SHADOWSOCKS, Authentication.Shadowsocks("2022-blake3-aes-128-gcm", "k", plugin = "obfs-local", pluginOptions = "obfs=http;obfs-host=x"))
        val ob = gen.buildOutbound(p)
        assertEquals("shadowsocks", ob["type"]!!.jsonPrimitive.content)
        assertEquals("obfs-local", ob["plugin"]!!.jsonPrimitive.content)
        assertEquals("obfs=http;obfs-host=x", ob["plugin_opts"]!!.jsonPrimitive.content)
        assertNull(ob["tls"])
    }

    @Test
    fun `hysteria2 with obfs and port hopping`() {
        val p = base(Protocol.HYSTERIA2, Authentication.Hysteria2(password = "pw", obfsType = "salamander", obfsPassword = "op", ports = "20000-30000,40000"),
            TlsSettings(enabled = true, serverName = "h.example.com", allowInsecure = true))
        val ob = gen.buildOutbound(p)
        assertEquals("hysteria2", ob["type"]!!.jsonPrimitive.content)
        assertEquals("salamander", ob["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("20000:30000", "40000:40000"), ob["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content })
        val tls = ob["tls"]!!.jsonObject
        assertEquals(listOf("h3"), tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tls["insecure"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tuic sets congestion control and h3 alpn`() {
        val p = base(Protocol.TUIC, Authentication.Tuic(uuid = "u", password = "p", congestionControl = "bbr"), TlsSettings(enabled = true, serverName = "t.example.com"))
        val ob = gen.buildOutbound(p)
        assertEquals("bbr", ob["congestion_control"]!!.jsonPrimitive.content)
        assertEquals("native", ob["udp_relay_mode"]!!.jsonPrimitive.content)
        assertEquals(listOf("h3"), ob["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `wireguard becomes an endpoint not an outbound`() {
        val p = base(Protocol.WIREGUARD, Authentication.WireGuard(privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"), reserved = listOf(1, 2, 3)))
        val doc = gen.generateDocument(p, CoreStartOptions())
        val endpoints = doc["endpoints"]!!.jsonArray
        assertEquals(1, endpoints.size)
        val ep = endpoints[0].jsonObject
        assertEquals("wireguard", ep["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", ep["tag"]!!.jsonPrimitive.content)
        val peer = ep["peers"]!!.jsonArray[0].jsonObject
        assertEquals("srv.example.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals(listOf(1, 2, 3), peer["reserved"]!!.jsonArray.map { it.jsonPrimitive.int })
        // only direct outbound remains
        val outbounds = doc["outbounds"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("direct"), outbounds)
    }

    @Test
    fun `full document has tun inbound dns route and final proxy`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"), TlsSettings(enabled = true, serverName = "s"))
        val doc = gen.generateDocument(p, CoreStartOptions(mtu = 1500, ipv6 = false, includePackages = listOf("com.a")))
        val tun = doc["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals(1500, tun["mtu"]!!.jsonPrimitive.int)
        assertEquals(listOf("172.19.0.1/30"), tun["address"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("com.a"), tun["include_package"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tun["auto_route"]!!.jsonPrimitive.boolean)

        val dns = doc["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals("https", servers[0]["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[0]["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", servers[0]["path"]!!.jsonPrimitive.content)
        assertEquals("proxy", servers[0]["detour"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1]["type"]!!.jsonPrimitive.content)
        // proxy hostname resolved directly
        val rule = dns["rules"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("srv.example.com"), rule["domain"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("dns-direct", rule["server"]!!.jsonPrimitive.content)

        val route = doc["route"]!!.jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean)
        val actions = route["rules"]!!.jsonArray.map { it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: it.jsonObject["outbound"]!!.jsonPrimitive.content }
        assertEquals(listOf("sniff", "hijack-dns", "direct"), actions)
    }

    @Test
    fun `global DNS and routing options are honoured and profile DNS override wins`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"), TlsSettings(enabled = true, serverName = "s"))
        val opts = CoreStartOptions(remoteDns = "tls://9.9.9.9", directDns = "udp://192.168.1.1", bypassPrivate = false, strictRoute = false)
        val doc = gen.generateDocument(p, opts)
        val servers = doc["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals("tls", servers[0]["type"]!!.jsonPrimitive.content); assertEquals("9.9.9.9", servers[0]["server"]!!.jsonPrimitive.content)
        assertEquals("udp", servers[1]["type"]!!.jsonPrimitive.content); assertEquals("192.168.1.1", servers[1]["server"]!!.jsonPrimitive.content)
        val rules = doc["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(2, rules.size, "no ip_is_private rule when bypassPrivate=false")
        assertFalse(doc["inbounds"]!!.jsonArray[0].jsonObject["strict_route"]!!.jsonPrimitive.boolean)

        val withProfileDns = p.copy(dns = p.dns.copy(remoteDns = "https://dns.google/dns-query"))
        val s2 = gen.generateDocument(withProfileDns, opts)["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject
        assertEquals("dns.google", s2["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user routing rules are emitted in order and domains and cidrs split and block maps to reject`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"))
        val opts = CoreStartOptions(
            bypassPrivate = true,
            rules = listOf(
                RoutingRule(RouteAction.DIRECT, domainSuffixes = listOf(".ir"), ipCidrs = listOf("10.10.0.0/16")),
                RoutingRule(RouteAction.BLOCK, domainKeywords = listOf("ads")),
                RoutingRule(RouteAction.PROXY, domains = listOf("example.com")),
                RoutingRule(RouteAction.DIRECT), // empty → dropped
            ),
        )
        val rules = gen.generateDocument(p, opts)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        // sniff, hijack-dns, ip_is_private, then: .ir domain rule, .ir cidr rule, ads reject, example.com proxy
        assertEquals(7, rules.size)
        assertEquals(".ir", rules[3]["domain_suffix"]!!.jsonArray[0].jsonPrimitive.content); assertEquals("direct", rules[3]["outbound"]!!.jsonPrimitive.content)
        assertNull(rules[3]["ip_cidr"])
        assertEquals("10.10.0.0/16", rules[4]["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content); assertEquals("direct", rules[4]["outbound"]!!.jsonPrimitive.content)
        assertEquals("reject", rules[5]["action"]!!.jsonPrimitive.content); assertNull(rules[5]["outbound"])
        assertEquals("proxy", rules[6]["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generate is deterministic`() {
        val p = base(Protocol.VLESS, Authentication.Vless("u"), TlsSettings(enabled = true, serverName = "s"), Transport.Grpc("svc"))
        assertEquals(gen.generate(p, CoreStartOptions()), gen.generate(p, CoreStartOptions()))
    }

    @Test
    fun `custom routing and dns fragments are embedded verbatim`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"))
        val opts = CoreStartOptions(
            routingConfig = """{"rules":[{"domain_suffix":[".ir"],"outbound":"direct"}],"final":"proxy"}""",
            dnsConfig = """{"servers":[{"tag":"x","type":"local"}],"final":"x"}""",
        )
        val doc = gen.generateDocument(p, opts)
        assertEquals("x", doc["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertEquals(listOf(".ir"), doc["route"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `core specific overrides merge into outbound`() {
        val p = base(Protocol.SOCKS, Authentication.None).copy(coreSpecificOptions = mapOf("singbox.outbound.udp_over_tcp" to "true", "singbox.outbound.network" to "tcp"))
        val ob = gen.buildOutbound(p)
        assertTrue(ob["udp_over_tcp"]!!.jsonPrimitive.boolean)
        assertEquals("tcp", ob["network"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unsupported transport is rejected with UnsupportedProtocol`() {
        val p = base(Protocol.VLESS, Authentication.Vless("u"), transport = Transport.Unsupported("xhttp"))
        val e = assertFailsWith<CoreException> { gen.generate(p, CoreStartOptions()) }
        assertTrue(e.error.technicalDetail.contains("xhttp"))
    }

    @Test
    fun `wrong credential type is InvalidConfiguration`() {
        val p = base(Protocol.VLESS, Authentication.Trojan("x"))
        assertFailsWith<CoreException> { gen.buildOutbound(p) }
    }

    @Test
    fun `dns server spec parsing covers formats`() {
        fun t(spec: String) = gen.dnsServer("t", spec, null)
        assertEquals("local", t("local")["type"]!!.jsonPrimitive.content)
        t("8.8.8.8").let { assertEquals("udp", it["type"]!!.jsonPrimitive.content); assertEquals("8.8.8.8", it["server"]!!.jsonPrimitive.content) }
        t("tls://dns.google").let { assertEquals("tls", it["type"]!!.jsonPrimitive.content); assertEquals("dns.google", it["server"]!!.jsonPrimitive.content) }
        t("https://dns.google/dns-query").let { assertEquals("https", it["type"]!!.jsonPrimitive.content); assertEquals("/dns-query", it["path"]!!.jsonPrimitive.content) }
        t("udp://[2001:4860:4860::8888]:53").let { assertEquals("2001:4860:4860::8888", it["server"]!!.jsonPrimitive.content); assertEquals(53, it["server_port"]!!.jsonPrimitive.int) }
        t("h3://1.1.1.1:443/dns-query").let { assertEquals("h3", it["type"]!!.jsonPrimitive.content); assertEquals(443, it["server_port"]!!.jsonPrimitive.int) }
    }

    @Test
    fun `secrets are not leaked into log string`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("supersecret"))
        val s = p.toLogString()
        assertFalse(s.contains("supersecret"))
        assertFalse(s.contains("srv.example.com"))
    }

    @Test
    fun `output parses as JSON`() {
        val p = base(Protocol.HTTP, Authentication.UserPassword("u", "p"), TlsSettings(enabled = true))
        val text = gen.generate(p, CoreStartOptions())
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive("http"), parsed["outbounds"]!!.jsonArray[0].jsonObject["type"])
    }
}
