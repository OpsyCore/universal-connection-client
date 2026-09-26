package io.ucc.core.config

import io.ucc.core.config.parser.LinkParser
import io.ucc.core.config.parser.SingBoxJsonImporter
import io.ucc.core.model.Authentication
import io.ucc.core.model.Protocol
import io.ucc.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.ucc.core.platform.Base64Codec

class ConfigImporterTest {
    private var n = 0
    private val ids = IdGenerator { "id-${++n}" }
    private val importer = ConfigImporter(LinkParser(ids, { 1L }), SingBoxJsonImporter(ids, { 1L }))
    private fun b64(s: String) = Base64Codec.encode(s.encodeToByteArray())

    private val links = """
        # comment line
        trojan://pw@1.2.3.4:443#A
        vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls#B
        broken://x
        ss://${b64("aes-256-gcm:pw")}@5.6.7.8:8388#C
    """.trimIndent()

    @Test fun `multi-line links with one failure`() {
        val r = importer.import(links)
        assertEquals(InputFormat.SHARE_LINKS, r.format)
        assertEquals(listOf("A", "B", "C"), r.profiles.map { it.name })
        assertEquals(1, r.failures.size)
        assertIs<ConfigError.UnknownScheme>(r.failures[0].error)
    }

    @Test fun `base64 subscription body`() {
        val r = importer.import(b64(links))
        assertEquals(InputFormat.BASE64_SHARE_LINKS, r.format)
        assertEquals(3, r.profiles.size)
    }

    @Test fun `base64 subscription body url-safe and wrapped`() {
        val wrapped = Base64Codec.encodeUrlSafeNoPadding(links.encodeToByteArray()).chunked(64).joinToString("\n")
        assertEquals(3, importer.import(wrapped).profiles.size)
    }

    @Test fun `duplicates inside one import are collapsed by fingerprint`() {
        val r = importer.import("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.4:443#A-copy")
        assertEquals(1, r.profiles.size)
    }

    @Test fun `links separated by whitespace on a single line`() {
        val r = importer.import("trojan://pw@1.2.3.4:443#A trojan://pw@1.2.3.5:443#B")
        assertEquals(2, r.profiles.size)
    }

    @Test fun `empty and unknown`() {
        assertEquals(InputFormat.EMPTY, importer.import("  \n").format)
        val u = importer.import("just some text")
        assertEquals(InputFormat.UNKNOWN, u.format)
        assertEquals(1, u.failures.size)
    }

    @Test fun `failure snippets never contain credentials`() {
        val r = importer.import("vless://not-a-uuid-but-secret@1.2.3.4:99999")
        assertEquals(1, r.failures.size)
        assertFalse(r.failures[0].snippet.contains("secret"))
        assertTrue(r.failures[0].snippet.contains("***@"))
    }

    @Test fun `sing-box json document extracts proxy outbounds and skips utility ones`() {
        val doc = """
        {
          "log": {"level": "info"},
          "outbounds": [
            {"type": "selector", "tag": "proxy", "outbounds": ["a", "b"]},
            {"type": "vless", "tag": "a", "server": "1.2.3.4", "server_port": 443, "uuid": "b831381d-6324-4d53-ad4f-8cda48b30811", "flow": "xtls-rprx-vision",
             "tls": {"enabled": true, "server_name": "x.com", "utls": {"enabled": true, "fingerprint": "chrome"}, "reality": {"enabled": true, "public_key": "PBK", "short_id": "01"}}},
            {"type": "shadowsocks", "tag": "b", "server": "5.6.7.8", "server_port": 8388, "method": "aes-256-gcm", "password": "pw"},
            {"type": "hysteria2", "tag": "c", "server": "9.9.9.9", "server_port": 443, "password": "pw", "obfs": {"type": "salamander", "password": "o"}, "tls": {"enabled": true, "server_name": "h.com", "insecure": true}},
            {"type": "vmess", "tag": "d", "server": "7.7.7.7", "server_port": 80, "uuid": "b831381d-6324-4d53-ad4f-8cda48b30811", "transport": {"type": "ws", "path": "/w", "headers": {"Host": "h"}}},
            {"type": "trojan", "tag": "broken", "server": "1.1.1.1", "server_port": 443},
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"}
          ],
          "endpoints": [
            {"type": "wireguard", "tag": "wg", "address": ["10.0.0.2/32"], "private_key": "PRIV", "peers": [{"address": "8.8.8.8", "port": 51820, "public_key": "PUB", "reserved": [1,2,3]}]}
          ]
        }
        """.trimIndent()
        val r = importer.import(doc)
        assertEquals(InputFormat.SING_BOX_JSON, r.format)
        assertEquals(listOf("a", "b", "c", "d", "wg"), r.profiles.map { it.name })
        val a = r.profiles[0]
        assertEquals("PBK", a.tls.reality?.publicKey); assertEquals("chrome", a.tls.fingerprint)
        assertEquals("xtls-rprx-vision", (a.authentication as Authentication.Vless).flow)
        val c = r.profiles[2]
        assertEquals("salamander", (c.authentication as Authentication.Hysteria2).obfsType); assertTrue(c.tls.allowInsecure)
        val d = r.profiles[3]
        assertEquals(Transport.WebSocket(path = "/w", host = "h"), d.transport)
        val wg = r.profiles[4]
        assertEquals(Protocol.WIREGUARD, wg.protocol); assertEquals("8.8.8.8", wg.address); assertEquals(51820, wg.port)
        assertEquals(listOf(1, 2, 3), (wg.authentication as Authentication.WireGuard).reserved)
        assertEquals(1, r.failures.size)
        assertIs<ConfigError.MissingField>(r.failures[0].error)
        assertTrue(r.failures[0].snippet.contains("broken"))
    }

    @Test fun `bare outbound object`() {
        val r = importer.import("""{"type":"trojan","tag":"t","server":"1.1.1.1","server_port":443,"password":"pw"}""")
        assertEquals(1, r.profiles.size)
        assertEquals(Protocol.TROJAN, r.profiles[0].protocol)
    }

    @Test fun `invalid json classified`() {
        val r = importer.import("{ nope")
        assertEquals(InputFormat.SING_BOX_JSON, r.format)
        assertIs<ConfigError.InvalidJson>(r.failures.single().error)
    }

    @Test fun `import is deterministic`() {
        val a = ConfigImporter(LinkParser({ "x" }, { 1L })).import(links).profiles
        val b = ConfigImporter(LinkParser({ "x" }, { 1L })).import(links).profiles
        assertEquals(a, b)
    }
}
