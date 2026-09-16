package io.ucc.core.config

import io.ucc.core.config.export.ShareLinkExporter
import io.ucc.core.config.parser.LinkParser
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShareLinkExporterTest {
    private var n = 0
    private val parser = LinkParser({ "id-${++n}" }, { 1L })
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun parse(link: String): ConnectionProfile {
        val r = parser.parse(link)
        assertIs<ParseResult.Success>(r, "expected success for $link, got $r")
        return r.profile
    }

    /** export(parse(x)) must re-parse to the same fingerprint and the same name. */
    private fun roundTrip(link: String): ConnectionProfile {
        val original = parse(link)
        val exported = ShareLinkExporter.export(original) ?: error("no export for $link")
        val again = parse(exported)
        assertEquals(original.fingerprint, again.fingerprint, "fingerprint drift for $link\n  exported: $exported")
        assertEquals(original.name, again.name)
        return again
    }

    @Test fun `vless reality vision round-trips`() {
        roundTrip("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp#%F0%9F%87%A9%F0%9F%87%AA%20Berlin")
    }

    @Test fun `vless ws with early data and host round-trips`() {
        roundTrip("vless://b831381d-6324-4d53-ad4f-8cda48b30811@cdn.example.com:443?type=ws&security=tls&path=%2Fws%3Fed%3D2048&host=cdn.example.com&sni=cdn.example.com#ws")
    }

    @Test fun `vless grpc multi round-trips`() {
        roundTrip("vless://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?type=grpc&serviceName=grpcsvc&security=tls&mode=multi#g")
    }

    @Test fun `vmess exports as v2rayN base64 JSON and round-trips`() {
        val json = """{"v":"2","ps":"HK-01","add":"hk.example.com","port":"443","id":"a3482e88-686a-4a58-8126-99c9df64b7bf","aid":"0","scy":"auto","net":"ws","type":"none","host":"hk.example.com","path":"/v2","tls":"tls","sni":"hk.example.com","alpn":"h2,http/1.1","fp":"firefox"}"""
        val p = parse("vmess://" + b64(json))
        val exported = ShareLinkExporter.export(p)!!
        assertTrue(exported.startsWith("vmess://"))
        assertTrue(String(Base64.getDecoder().decode(exported.removePrefix("vmess://"))).startsWith("{"))
        roundTrip("vmess://" + b64(json))
    }

    @Test fun `trojan with special characters in password round-trips`() {
        val again = roundTrip("trojan://p%40ss%3Aw%2Brd@t.example.com:443?type=ws&path=%2Ftr&sni=t.example.com&allowInsecure=1#tr")
        assertEquals("p@ss:w+rd", (again.authentication as io.ucc.core.model.Authentication.Trojan).password)
    }

    @Test fun `trojan without tls keeps security none`() {
        val again = roundTrip("trojan://pw@1.2.3.4:80?security=none#plain")
        assertTrue(!again.tls.enabled)
    }

    @Test fun `shadowsocks with plugin round-trips`() {
        roundTrip("ss://${b64("aes-256-gcm:pw")}@5.6.7.8:8388/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dwww.bing.com#C")
    }

    @Test fun `shadowsocks 2022 round-trips`() {
        roundTrip("ss://2022-blake3-aes-128-gcm:YctPZ6U7xPPcU%2Bgp3u%2B0tx%2FteRs%3D@1.2.3.4:8388#ss22")
    }

    @Test fun `hysteria2 with obfs round-trips`() {
        roundTrip("hysteria2://letmein@example.com:443/?obfs=salamander&obfs-password=gawrgura&sni=example.com&insecure=1&mport=20000-30000#hy2")
    }

    @Test fun `hysteria v1 round-trips`() {
        roundTrip("hysteria://host.example.com:36712?protocol=udp&auth=123456&peer=sni.example.com&insecure=1&upmbps=100&downmbps=100&alpn=h3&obfs=xplus#hy1")
    }

    @Test fun `tuic round-trips`() {
        roundTrip("tuic://b831381d-6324-4d53-ad4f-8cda48b30811:pass@1.2.3.4:443?congestion_control=bbr&udp_relay_mode=quic&alpn=h3&sni=t.example.com#tuic")
    }

    @Test fun `wireguard round-trips`() {
        val priv = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val pub = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        roundTrip("wireguard://${java.net.URLEncoder.encode(priv, "UTF-8")}@1.2.3.4:51820?publickey=${java.net.URLEncoder.encode(pub, "UTF-8")}&address=10.0.0.2/32,fd00::2/128&reserved=1,2,3&mtu=1280#wg")
    }

    @Test fun `socks with credentials and anonymous http round-trip`() {
        roundTrip("socks5://user:p%40ss@1.2.3.4:1080#S")
        val h = roundTrip("http://1.2.3.4:3128#H")
        assertEquals(Protocol.HTTP, h.protocol)
    }

    @Test fun `ipv6 hosts are bracketed`() {
        val exported = ShareLinkExporter.export(parse("trojan://pw@[2001:db8::1]:443#v6"))!!
        assertTrue(exported.contains("@[2001:db8::1]:443"))
        roundTrip("trojan://pw@[2001:db8::1]:443#v6")
    }

    @Test fun `exportAll emits one link per line and base64 variant decodes to it`() {
        val ps = listOf(parse("trojan://pw@1.2.3.4:443#A"), parse("trojan://pw@1.2.3.5:443#B"))
        val text = ShareLinkExporter.exportAll(ps)
        assertEquals(2, text.lines().size)
        assertEquals(text, String(Base64.getDecoder().decode(ShareLinkExporter.exportAllBase64(ps))))
    }
}
