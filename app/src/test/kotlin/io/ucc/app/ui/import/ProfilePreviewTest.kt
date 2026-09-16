package io.ucc.app.ui.import

import io.ucc.core.config.ParseResult
import io.ucc.core.config.parser.LinkParser
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePreviewTest {
    private val parser = LinkParser({ "id" }, { 1L })
    private fun preview(link: String) = ProfilePreview.of((parser.parse(link) as ParseResult.Success).profile)
    private fun ProfilePreview.allText() = listOf(name, protocol, server, transport, security).joinToString(" ") + details.joinToString(" ") { it.first + "=" + it.second }

    @Test fun `vless reality preview shows protocol server port transport security and no uuid`() {
        val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
        val p = preview("vless://$uuid@1.2.3.4:443?security=reality&sni=www.microsoft.com&fp=chrome&pbk=PBKPBK&sid=6ba8&flow=xtls-rprx-vision&type=tcp#Berlin")
        assertEquals("Berlin", p.name); assertEquals("VLESS", p.protocol); assertEquals("1.2.3.4", p.server); assertEquals(443, p.port)
        assertEquals("TCP", p.transport)
        assertTrue(p.security.startsWith("REALITY")); assertTrue(p.security.contains("www.microsoft.com"))
        assertTrue(p.details.any { it.first == "flow" })
        assertTrue(p.hasCredentials)
        assertFalse(p.allText().contains(uuid))
    }

    @Test fun `passwords and keys never appear`() {
        val ss = preview("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:SuperSecretPw".toByteArray()) + "@5.6.7.8:8388#SS")
        assertFalse(ss.allText().contains("SuperSecretPw")); assertTrue(ss.details.any { it == ("method" to "aes-256-gcm") })
        val tr = preview("trojan://TrojanSecret@t.example.com:443?allowInsecure=1#T")
        assertFalse(tr.allText().contains("TrojanSecret")); assertTrue(tr.insecureTls)
        val hy = preview("hy2://HyPw@h.example.com:443?obfs=salamander&obfs-password=ObfsSecret#H")
        assertFalse(hy.allText().contains("HyPw")); assertFalse(hy.allText().contains("ObfsSecret")); assertEquals("QUIC", hy.transport)
        val priv = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val pub = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        val wg = preview("wg://${java.net.URLEncoder.encode(priv, "UTF-8")}@1.2.3.4:51820?publickey=${java.net.URLEncoder.encode(pub, "UTF-8")}&address=10.0.0.2#W")
        assertFalse(wg.allText().contains(priv)); assertTrue(wg.details.any { it.first == "addresses" })
        val socks = preview("socks5://user:P%40ss@1.2.3.4:1080#S")
        assertFalse(socks.allText().contains("P@ss")); assertTrue(socks.details.any { it == ("user" to "user") })
    }

    @Test fun `ws transport shows path and host`() {
        val p = preview("vmess://b831381d-6324-4d53-ad4f-8cda48b30811@h.example.com:443?type=ws&path=/v2&host=cdn.example.com&security=tls#W")
        assertTrue(p.transport.startsWith("WebSocket /v2")); assertTrue(p.transport.contains("cdn.example.com"))
        assertTrue(p.security.startsWith("TLS"))
    }
}
