package io.ucc.core.config

import io.ucc.core.config.parser.LinkParser
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Base64

class LinkParserTest {
    private var counter = 0
    private val parser = LinkParser(ids = { "id-${++counter}" }, time = { 1_000L })

    private fun ok(link: String): ConnectionProfile {
        val r = parser.parse(link)
        assertIs<ParseResult.Success>(r, "expected success for $link, got $r")
        return r.profile
    }

    private fun fail(link: String): ConfigError {
        val r = parser.parse(link)
        assertIs<ParseResult.Failure>(r, "expected failure for $link, got $r")
        return r.error
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun `vless reality vision`() {
        val p = ok("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp&headerType=none#%F0%9F%87%A9%F0%9F%87%AA%20Berlin")
        assertEquals(Protocol.VLESS, p.protocol)
        assertEquals("🇩🇪 Berlin", p.name)
        assertEquals("1.2.3.4", p.address); assertEquals(443, p.port)
        assertEquals(Transport.Tcp, p.transport)
        val auth = assertIs<Authentication.Vless>(p.authentication)
        assertEquals("xtls-rprx-vision", auth.flow)
        assertTrue(p.tls.enabled)
        assertEquals("www.microsoft.com", p.tls.serverName)
        assertEquals("chrome", p.tls.fingerprint)
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", p.tls.reality?.publicKey)
        assertEquals("6ba85179e30d4fc2", p.tls.reality?.shortId)
    }

    @Test fun `vless ws with early data in path and host`() {
        val p = ok("vless://b831381d-6324-4d53-ad4f-8cda48b30811@cdn.example.com:443?type=ws&security=tls&path=%2Fws%3Fed%3D2048&host=cdn.example.com&sni=cdn.example.com#ws")
        val ws = assertIs<Transport.WebSocket>(p.transport)
        assertEquals("/ws", ws.path)
        assertEquals(2048, ws.maxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", ws.earlyDataHeaderName)
        assertEquals("cdn.example.com", ws.host)
    }

    @Test fun `vless grpc`() {
        val p = ok("vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?type=grpc&serviceName=grpcsvc&security=tls&mode=multi#g")
        val g = assertIs<Transport.Grpc>(p.transport)
        assertEquals("grpcsvc", g.serviceName); assertTrue(g.multiMode)
    }

    @Test fun `vless xhttp is recorded as unsupported transport not rejected`() {
        val p = ok("vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?type=xhttp&path=%2Fx&security=tls#x")
        val u = assertIs<Transport.Unsupported>(p.transport)
        assertEquals("xhttp", u.name)
        assertEquals("/x", u.rawOptions["path"])
    }

    @Test fun `vless reality without pbk fails with missing field`() {
        val e = fail("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=reality&sni=a.com#x")
        assertIs<ConfigError.MissingField>(e)
        assertTrue(e.detail.contains("pbk"))
    }

    @Test fun `vless missing port and bad port classified`() {
        assertIs<ConfigError.MissingField>(fail("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4?security=tls"))
        assertIs<ConfigError.InvalidField>(fail("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:99999"))
    }

    @Test fun `vmess base64 json v2rayN format`() {
        val json = """{"v":"2","ps":"HK-01","add":"hk.example.com","port":"443","id":"a3482e88-686a-4a58-8126-99c9df64b7bf","aid":"0","scy":"auto","net":"ws","type":"none","host":"hk.example.com","path":"/v2","tls":"tls","sni":"hk.example.com","alpn":"h2,http/1.1","fp":"firefox"}"""
        val p = ok("vmess://" + b64(json))
        assertEquals(Protocol.VMESS, p.protocol)
        assertEquals("HK-01", p.name)
        assertEquals("hk.example.com", p.address); assertEquals(443, p.port)
        val ws = assertIs<Transport.WebSocket>(p.transport)
        assertEquals("/v2", ws.path); assertEquals("hk.example.com", ws.host)
        assertTrue(p.tls.enabled); assertEquals(listOf("h2", "http/1.1"), p.tls.alpn); assertEquals("firefox", p.tls.fingerprint)
        assertEquals("a3482e88-686a-4a58-8126-99c9df64b7bf", (p.authentication as Authentication.Vmess).uuid)
    }

    @Test fun `vmess base64 without padding and with alterId warns`() {
        val json = """{"v":"2","ps":"legacy","add":"1.1.1.1","port":8080,"id":"a3482e88-686a-4a58-8126-99c9df64b7bf","aid":64,"net":"tcp","tls":""}"""
        val r = parser.parse("vmess://" + b64(json).trimEnd('='))
        val s = assertIs<ParseResult.Success>(r)
        assertEquals(8080, s.profile.port)
        assertFalse(s.profile.tls.enabled)
        assertTrue(s.warnings.any { it.contains("alterId") })
    }

    @Test fun `vmess uri form`() {
        val p = ok("vmess://a3482e88-686a-4a58-8126-99c9df64b7bf@h.example.com:443?type=ws&security=tls&path=/x#u")
        assertIs<Transport.WebSocket>(p.transport)
        assertEquals("u", p.name)
    }

    @Test fun `vmess garbage payload is invalid json`() {
        assertIs<ConfigError.InvalidJson>(fail("vmess://" + b64("{not json")))
    }

    @Test fun `trojan defaults to tls and reads ws`() {
        val p = ok("trojan://p%40ss@t.example.com:443?type=ws&path=%2Ftr&sni=t.example.com&allowInsecure=1#tr")
        assertEquals("p@ss", (p.authentication as Authentication.Trojan).password)
        assertTrue(p.tls.enabled); assertTrue(p.tls.allowInsecure)
        assertIs<Transport.WebSocket>(p.transport)
    }

    @Test fun `trojan security none disables tls`() {
        assertFalse(ok("trojan://pw@1.2.3.4:80?security=none").tls.enabled)
    }

    @Test fun `shadowsocks sip002 base64 userinfo with plugin`() {
        val p = ok("ss://" + b64("aes-256-gcm:secret") + "@ss.example.com:8388/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dwww.bing.com#SS")
        val a = assertIs<Authentication.Shadowsocks>(p.authentication)
        assertEquals("aes-256-gcm", a.method); assertEquals("secret", a.password)
        assertEquals("obfs-local", a.plugin); assertEquals("obfs=http;obfs-host=www.bing.com", a.pluginOptions)
        assertEquals(8388, p.port); assertEquals("SS", p.name)
    }

    @Test fun `shadowsocks 2022 plain userinfo`() {
        val p = ok("ss://2022-blake3-aes-128-gcm:YctPZ6U7xPPcU%2Bgp3u%2B0tx%2FteRl1kBvpyrANtgz%2F6qc%3D@1.2.3.4:8443#x")
        val a = p.authentication as Authentication.Shadowsocks
        assertEquals("2022-blake3-aes-128-gcm", a.method)
        assertEquals("YctPZ6U7xPPcU+gp3u+0tx/teRl1kBvpyrANtgz/6qc=", a.password)
    }

    @Test fun `shadowsocks legacy whole-base64 form`() {
        val p = ok("ss://" + b64("chacha20-ietf-poly1305:pw123@5.6.7.8:443") + "#legacy")
        assertEquals("5.6.7.8", p.address); assertEquals(443, p.port)
        assertEquals("pw123", (p.authentication as Authentication.Shadowsocks).password)
    }

    @Test fun `shadowsocks unknown cipher unsupported`() {
        assertIs<ConfigError.Unsupported>(fail("ss://" + b64("rot13:pw") + "@1.2.3.4:1"))
    }

    @Test fun `hysteria2 with obfs and mport`() {
        val p = ok("hy2://pass@h2.example.com:443/?obfs=salamander&obfs-password=ob&sni=h2.example.com&insecure=1&mport=20000-30000#H2")
        assertEquals(Protocol.HYSTERIA2, p.protocol)
        val a = assertIs<Authentication.Hysteria2>(p.authentication)
        assertEquals("pass", a.password); assertEquals("salamander", a.obfsType); assertEquals("ob", a.obfsPassword); assertEquals("20000-30000", a.ports)
        assertTrue(p.tls.enabled); assertTrue(p.tls.allowInsecure)
        assertEquals(Transport.None, p.transport)
    }

    @Test fun `hysteria2 default port 443`() { assertEquals(443, ok("hysteria2://pw@h.example.com").port) }

    @Test fun `hysteria2 obfs without password fails`() {
        assertIs<ConfigError.MissingField>(fail("hy2://pw@h.example.com:443?obfs=salamander"))
    }

    @Test fun `hysteria v1`() {
        val p = ok("hysteria://h1.example.com:36712?protocol=udp&auth=tok&peer=h1.example.com&insecure=1&upmbps=20&downmbps=100&alpn=h3#H1")
        val a = assertIs<Authentication.Hysteria>(p.authentication)
        assertEquals("tok", a.auth); assertEquals(20, a.upMbps); assertEquals(100, a.downMbps)
        assertEquals(listOf("h3"), p.tls.alpn)
    }

    @Test fun `hysteria v1 non-udp protocol unsupported`() {
        assertIs<ConfigError.Unsupported>(fail("hysteria://h.example.com:1?protocol=faketcp"))
    }

    @Test fun `tuic v5`() {
        val p = ok("tuic://a3482e88-686a-4a58-8126-99c9df64b7bf:pw@t.example.com:443?congestion_control=bbr&udp_relay_mode=quic&alpn=h3&sni=t.example.com&allow_insecure=0#TUIC")
        val a = assertIs<Authentication.Tuic>(p.authentication)
        assertEquals("bbr", a.congestionControl); assertEquals("quic", a.udpRelayMode); assertEquals("pw", a.password)
        assertEquals(listOf("h3"), p.tls.alpn)
    }

    @Test fun `tuic invalid uuid and cc`() {
        assertIs<ConfigError.InvalidField>(fail("tuic://notauuid:pw@t.example.com:443"))
        assertIs<ConfigError.InvalidField>(fail("tuic://a3482e88-686a-4a58-8126-99c9df64b7bf:pw@t.example.com:443?congestion_control=warp"))
    }

    @Test fun `wireguard link`() {
        val priv = b64(ByteArray(32) { 1 }.toString(Charsets.ISO_8859_1))
        val pub = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val p = ok("wireguard://${java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }), "UTF-8")}@engage.cloudflareclient.com:2408?publickey=${java.net.URLEncoder.encode(pub, "UTF-8")}&address=172.16.0.2/32,2606:4700:110:8949::1&reserved=1,2,3&mtu=1280#WARP")
        val a = assertIs<Authentication.WireGuard>(p.authentication)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:8949::1/128"), a.localAddresses)
        assertEquals(listOf(1, 2, 3), a.reserved); assertEquals(1280, a.mtu)
        assertEquals(pub, a.peerPublicKey)
        assertEquals(2408, p.port)
    }

    @Test fun `wireguard bad key length`() {
        assertIs<ConfigError.InvalidField>(fail("wg://c2hvcnQ=@1.2.3.4:51820?publickey=c2hvcnQ=&address=10.0.0.2"))
    }

    @Test fun `socks and http with auth and defaults`() {
        val s = ok("socks5://user:p%40ss@1.2.3.4:1080#S")
        assertEquals(Protocol.SOCKS, s.protocol)
        assertEquals(Authentication.UserPassword("user", "p@ss"), s.authentication)
        val h = ok("https://proxy.example.com#H")
        assertEquals(Protocol.HTTP, h.protocol); assertEquals(443, h.port); assertTrue(h.tls.enabled)
        val anon = ok("http://1.2.3.4:3128")
        assertEquals(Authentication.None, anon.authentication); assertFalse(anon.tls.enabled)
    }

    @Test fun `socks base64 userinfo`() {
        val s = ok("socks://" + b64("u:p") + "@1.2.3.4:1080")
        assertEquals(Authentication.UserPassword("u", "p"), s.authentication)
    }

    @Test fun `ipv6 host with brackets`() {
        val p = ok("trojan://pw@[2001:db8::1]:443#v6")
        assertEquals("2001:db8::1", p.address); assertEquals(443, p.port)
        assertNull(p.tls.serverName, "no SNI should be derived from an IP literal")
    }

    @Test fun `unknown scheme and non-uri`() {
        assertIs<ConfigError.UnknownScheme>(fail("naive+https://u:p@h:443"))
        assertIs<ConfigError.MalformedUri>(fail("hello world"))
    }

    @Test fun `unknown query params produce warnings not errors`() {
        val r = parser.parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls&foo=bar")
        val s = assertIs<ParseResult.Success>(r)
        assertTrue(s.warnings.any { it.contains("foo") })
    }

    @Test fun `ids and metadata come from injected sources and raw is kept`() {
        val link = "trojan://pw@1.2.3.4:443#a"
        val p = ok(link)
        assertEquals("id-1", p.id)
        assertEquals(1_000L, p.metadata.createdAtEpochMs)
        assertEquals(link, p.metadata.rawSource)
        assertNotNull(p.fingerprint)
    }

    @Test fun `fingerprint ignores name and id`() {
        val a = ok("trojan://pw@1.2.3.4:443#one")
        val b = ok("trojan://pw@1.2.3.4:443#two")
        assertEquals(a.fingerprint, b.fingerprint)
    }
}
