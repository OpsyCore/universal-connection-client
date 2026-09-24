package io.ucc.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Data-class toString must never print credentials: a stray `Log.d("$profile")` is the most common leak path. */
class AuthenticationToStringTest {
    private val secret = "S3cr3t-Value-XYZ"

    private val all = listOf(
        Authentication.Vless(uuid = secret, flow = "xtls-rprx-vision"),
        Authentication.Vmess(uuid = secret),
        Authentication.Trojan(password = secret),
        Authentication.Shadowsocks(method = "aes-256-gcm", password = secret),
        Authentication.Hysteria(auth = secret, upMbps = 10),
        Authentication.Hysteria2(password = secret, obfsType = "salamander", obfsPassword = secret),
        Authentication.Tuic(uuid = secret, password = secret),
        Authentication.WireGuard(privateKey = secret, peerPublicKey = "pub", preSharedKey = secret, localAddresses = listOf("10.0.0.2/32")),
        Authentication.UserPassword(username = "user", password = secret),
    )

    @Test fun `no Authentication toString contains the secret`() {
        for (a in all) assertFalse(a.toString().contains(secret), a.toString())
    }

    @Test fun `profile toString is safe too and still shows non-secret fields`() {
        val p = ConnectionProfile(id = "id", name = "n", protocol = Protocol.TROJAN, address = "h", port = 1, authentication = Authentication.Trojan(secret))
        val s = p.toString()
        assertFalse(s.contains(secret))
        assertTrue(s.contains("Trojan(password=***)"))
    }

    @Test fun `redaction does not affect equality or serialization`() {
        val a = Authentication.Trojan(secret); val b = Authentication.Trojan(secret)
        assertTrue(a == b && a.hashCode() == b.hashCode())
        val json = kotlinx.serialization.json.Json.encodeToString(Authentication.serializer(), a)
        assertTrue(json.contains(secret), "serialization (used for storage + fingerprint) must keep the real value")
    }
}
