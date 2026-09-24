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
        assertEquals("ipv4_only", doc["dns"]!!.jsonObject["strategy"]!!.jsonPrimitive.content, "IPv6 off → DNS strategy ipv4_only")
        val v6 = gen.generateDocument(p, CoreStartOptions(ipv6 = true))
        assertEquals(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"), v6["inbounds"]!!.jsonArray[0].jsonObject["address"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("prefer_ipv4", v6["dns"]!!.jsonObject["strategy"]!!.jsonPrimitive.content, "IPv6 on → unchanged default")

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
    fun `tls fragment is off by default and adds sing-box fragment fields to tcp tls outbounds only`() {
        val trojan = base(Protocol.TROJAN, Authentication.Trojan("pw"), TlsSettings(enabled = true, serverName = "s"))
        val off = gen.generateDocument(trojan, CoreStartOptions())["outbounds"]!!.jsonArray[0].jsonObject["tls"]!!.jsonObject
        assertNull(off["fragment"]); assertNull(off["record_fragment"]); assertNull(off["fragment_fallback_delay"])

        val on = gen.generateDocument(trojan, CoreStartOptions(tlsFragment = true))["outbounds"]!!.jsonArray[0].jsonObject["tls"]!!.jsonObject
        assertTrue(on["fragment"]!!.jsonPrimitive.boolean)
        assertTrue(on["record_fragment"]!!.jsonPrimitive.boolean)
        assertEquals("500ms", on["fragment_fallback_delay"]!!.jsonPrimitive.content)
        assertEquals("s", on["server_name"]!!.jsonPrimitive.content, "existing tls fields preserved")
        assertNull(on["length"]); assertNull(on["interval"]) // never Xray-style fields

        // vless + utls (no reality) → fragmented; vless + REALITY → untouched
        val vlessTls = base(Protocol.VLESS, Authentication.Vless("11111111-2222-3333-4444-555555555555"), TlsSettings(enabled = true, serverName = "s", fingerprint = "chrome"))
        assertTrue(gen.buildOutbound(vlessTls, CoreStartOptions(tlsFragment = true))["tls"]!!.jsonObject["fragment"]!!.jsonPrimitive.boolean)
        val reality = base(Protocol.VLESS, Authentication.Vless("11111111-2222-3333-4444-555555555555", flow = "xtls-rprx-vision"),
            TlsSettings(enabled = true, serverName = "www.microsoft.com", fingerprint = "chrome", reality = TlsSettings.Reality("pubkey", "ab12")))
        assertNull(gen.buildOutbound(reality, CoreStartOptions(tlsFragment = true))["tls"]!!.jsonObject["fragment"], "REALITY ClientHello is never fragmented")

        // QUIC protocol → untouched; plaintext (no tls) → untouched
        val hy2 = base(Protocol.HYSTERIA2, Authentication.Hysteria2("pw"), TlsSettings(enabled = true, serverName = "s"))
        assertNull(gen.buildOutbound(hy2, CoreStartOptions(tlsFragment = true))["tls"]!!.jsonObject["fragment"])
        val plainVmess = base(Protocol.VMESS, Authentication.Vmess("11111111-2222-3333-4444-555555555555"))
        assertNull(gen.buildOutbound(plainVmess, CoreStartOptions(tlsFragment = true))["tls"])
        val ss = base(Protocol.SHADOWSOCKS, Authentication.Shadowsocks("aes-128-gcm", "pw"))
        assertNull(gen.buildOutbound(ss, CoreStartOptions(tlsFragment = true))["tls"])
    }

    @Test
    fun `block quic is off by default and inserts a reject rule before bypass and user rules`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"))
        val off = gen.generateDocument(p, CoreStartOptions())["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(off.none { it["protocol"]?.jsonPrimitive?.contentOrNull == "quic" })

        val on = gen.generateDocument(p, CoreStartOptions(blockQuic = true, rules = listOf(RoutingRule(RouteAction.DIRECT, domainSuffixes = listOf(".ir")))))["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        // sniff, hijack-dns, quic reject, ip_is_private, user rule
        assertEquals(5, on.size)
        assertEquals("sniff", on[0]["action"]!!.jsonPrimitive.content)
        assertEquals("quic", on[2]["protocol"]!!.jsonPrimitive.content)
        assertEquals("reject", on[2]["action"]!!.jsonPrimitive.content)
        assertNull(on[2]["outbound"]); assertNull(on[2]["port"]); assertNull(on[2]["network"]) // no udp/443 rule, no deprecated block outbound
        assertTrue(on[3]["ip_is_private"]!!.jsonPrimitive.boolean)
        assertEquals(".ir", on[4]["domain_suffix"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(gen.generateDocument(p, CoreStartOptions(blockQuic = true))["outbounds"]!!.jsonArray.none { it.jsonObject["type"]!!.jsonPrimitive.content == "block" })
    }

    @Test
    fun `fake dns is off by default and uses the typed fakeip server with ipv6 range only when ipv6 is on`() {
        val p = base(Protocol.TROJAN, Authentication.Trojan("pw"))
        val off = gen.generateDocument(p, CoreStartOptions())["dns"]!!.jsonObject
        assertTrue(off["servers"]!!.jsonArray.none { it.jsonObject["type"]!!.jsonPrimitive.content == "fakeip" })
        assertNull(off["fakeip"], "legacy dns.fakeip block is never emitted")
        assertEquals(1, off["rules"]!!.jsonArray.size)

        val v6 = gen.generateDocument(p, CoreStartOptions(fakeDns = true, ipv6 = true))["dns"]!!.jsonObject
        val fake6 = v6["servers"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "fakeip" }
        assertEquals("dns-fakeip", fake6["tag"]!!.jsonPrimitive.content)
        assertEquals("198.18.0.0/15", fake6["inet4_range"]!!.jsonPrimitive.content)
        assertEquals("fc00::/18", fake6["inet6_range"]!!.jsonPrimitive.content)
        assertNull(v6["fakeip"])
        val rules6 = v6["rules"]!!.jsonArray.map { it.jsonObject }
        // proxy-host → direct, LAN suffixes → direct, then A+AAAA → fakeip
        assertEquals(3, rules6.size)
        assertEquals("dns-direct", rules6[0]["server"]!!.jsonPrimitive.content)
        assertTrue(rules6[1]["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf(".local", ".lan", ".home.arpa")))
        assertEquals("dns-direct", rules6[1]["server"]!!.jsonPrimitive.content)
        assertEquals(listOf("A", "AAAA"), rules6[2]["query_type"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("dns-fakeip", rules6[2]["server"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", v6["final"]!!.jsonPrimitive.content, "non-A/AAAA queries still go to the real remote resolver")
        assertTrue(v6["independent_cache"]!!.jsonPrimitive.boolean)

        // IPv6 off → strategy ipv4_only, no inet6_range, and only A queries are faked (no AAAA answers can ever be produced)
        val v4 = gen.generateDocument(p, CoreStartOptions(fakeDns = true, ipv6 = false))["dns"]!!.jsonObject
        assertEquals("ipv4_only", v4["strategy"]!!.jsonPrimitive.content)
        val fake4 = v4["servers"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "fakeip" }
        assertNull(fake4["inet6_range"])
        assertEquals(listOf("A"), v4["rules"]!!.jsonArray.last().jsonObject["query_type"]!!.jsonArray.map { it.jsonPrimitive.content })

        // bypassPrivate off → no LAN-suffix exclusion rule
        val noBypass = gen.generateDocument(p, CoreStartOptions(fakeDns = true, bypassPrivate = false))["dns"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(2, noBypass.size)
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

    // ------------------------------------------------------------------ v1.0.1 smart routing (rule-sets)

    private val withSets = SingBoxConfigGenerator(ruleSetDirectory = "/data/rulesets/")
    private val trojan = base(Protocol.TROJAN, Authentication.Trojan("pw"), TlsSettings(enabled = true))

    @Test
    fun `smart routing off emits no rule_set and no set rules`() {
        val doc = withSets.generateDocument(trojan, CoreStartOptions())
        val route = doc["route"]!!.jsonObject
        assertNull(route["rule_set"])
        assertTrue(route["rules"]!!.jsonArray.none { "rule_set" in it.jsonObject })
        assertTrue(doc["dns"]!!.jsonObject["rules"]!!.jsonArray.none { "rule_set" in it.jsonObject })
    }

    @Test
    fun `direct iran adds local rule-sets, direct route rules and a direct DNS rule before fakeip`() {
        val doc = withSets.generateDocument(trojan, CoreStartOptions(directIran = true, fakeDns = true))
        val route = doc["route"]!!.jsonObject
        val sets = route["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("geosite-ir", "geoip-ir"), sets.map { it["tag"]!!.jsonPrimitive.content })
        assertTrue(sets.all { it["type"]!!.jsonPrimitive.content == "local" && it["format"]!!.jsonPrimitive.content == "binary" })
        assertEquals("/data/rulesets/geosite-ir.srs", sets[0]["path"]!!.jsonPrimitive.content)
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val direct = rules.filter { it["outbound"]?.jsonPrimitive?.contentOrNull == "direct" && "rule_set" in it }
        assertEquals(listOf("geosite-ir", "geoip-ir"), direct.map { it["rule_set"]!!.jsonArray[0].jsonPrimitive.content })
        assertTrue(rules.none { it["action"]?.jsonPrimitive?.contentOrNull == "resolve" }, "no resolve action (would leak foreign names to the direct resolver)")
        val dnsRules = doc["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val ir = dnsRules.indexOfFirst { it["rule_set"]?.jsonArray?.get(0)?.jsonPrimitive?.content == "geosite-ir" }
        val fake = dnsRules.indexOfFirst { it["server"]?.jsonPrimitive?.contentOrNull == "dns-fakeip" }
        assertTrue(ir in 0 until fake, "Iranian names must be answered directly before the FakeIP catch-all")
        assertEquals("dns-direct", dnsRules[ir]["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `block ads rejects in route and dns and comes before user rules`() {
        val doc = withSets.generateDocument(trojan, CoreStartOptions(blockAds = true, rules = listOf(RoutingRule(RouteAction.PROXY, domainSuffixes = listOf(".ads.example")))))
        val route = doc["route"]!!.jsonObject
        assertEquals(listOf("geosite-category-ads-all"), route["rule_set"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content })
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val ads = rules.indexOfFirst { it["rule_set"] != null }
        val user = rules.indexOfFirst { it["domain_suffix"] != null }
        assertTrue(ads in 0 until user)
        assertEquals("reject", rules[ads]["action"]!!.jsonPrimitive.content)
        val dns = doc["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }.first { it["rule_set"] != null }
        assertEquals("reject", dns["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `smart routing without bundled rule-sets is an InvalidConfiguration, not a silent no-op`() {
        val e = assertFailsWith<CoreException> { gen.generateDocument(trojan, CoreStartOptions(directIran = true)) }
        assertTrue(e.error is io.ucc.core.engine.ConnectionError.InvalidConfiguration)
        assertFailsWith<CoreException> { gen.generateDocument(trojan, CoreStartOptions(blockAds = true)) }
    }

    // ------------------------------------------------------------------ v1.0.1 delay probe instance

    @Test
    fun `probe document has no inbound, one outbound per profile tagged by id, loopback clash api and no cache file`() {
        val a = trojan.copy(id = "a1")
        val b = base(Protocol.HYSTERIA2, Authentication.Hysteria2(password = "pw"), TlsSettings(enabled = true, serverName = "h.example")).copy(id = "b2", port = 8443)
        val wg = ConnectionProfile(id = "w", name = "wg", protocol = Protocol.WIREGUARD, address = "1.2.3.4", port = 51820,
            authentication = Authentication.WireGuard(privateKey = "cHJpdg==", peerPublicKey = "cHVi", localAddresses = listOf("10.0.0.2/32")))
        val doc = withSets.generateProbeDocument(listOf(a, b, wg), CoreStartOptions(tlsFragment = true), port = 24567, secret = "0123456789abcdef0123456789abcdef")
        assertNull(doc["inbounds"])
        assertNull(doc["endpoints"])
        val outs = doc["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a1", "b2", "direct"), outs.map { it["tag"]!!.jsonPrimitive.content })
        assertTrue(outs[0]["tls"]!!.jsonObject["fragment"]!!.jsonPrimitive.boolean, "probe outbounds use the same start options as the tunnel")
        val clash = doc["experimental"]!!.jsonObject["clash_api"]!!.jsonObject
        assertEquals("127.0.0.1:24567", clash["external_controller"]!!.jsonPrimitive.content)
        assertEquals("0123456789abcdef0123456789abcdef", clash["secret"]!!.jsonPrimitive.content)
        assertNull(doc["experimental"]!!.jsonObject["cache_file"])
        val route = doc["route"]!!.jsonObject
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean)
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        assertNull(route["rule_set"])
        // Parses as JSON and does not depend on rule-set files even when smart routing is on.
        Json.parseToJsonElement(gen.generateProbe(listOf(a), CoreStartOptions(directIran = true, blockAds = true), 30000, "0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `probe document rejects weak secrets and bad ports`() {
        assertFailsWith<IllegalArgumentException> { gen.generateProbeDocument(listOf(trojan), CoreStartOptions(), 1, "short") }
        assertFailsWith<IllegalArgumentException> { gen.generateProbeDocument(listOf(trojan), CoreStartOptions(), 0, "0123456789abcdef0123456789abcdef") }
    }
}
