package io.ucc.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSanitizerTest {
    private fun s(t: String) = LogSanitizer.sanitize(t)

    @Test fun `uuid`() = assertEquals("user <uuid> rejected", s("user b831381d-6324-4d53-ad4f-8cda48b30811 rejected"))

    @Test fun `key value pairs in several syntaxes`() {
        assertEquals("password=*** ok", s("password=hunter2 ok"))
        assertEquals("\"password\": \"***\"", s("\"password\": \"hunter2\""))
        assertEquals("private_key: ***", s("private_key: cGFzcw"))
        assertEquals("token=***&x=1", s("token=abc123&x=1"))
    }

    @Test fun `url userinfo and query secrets`() {
        assertEquals("https://***@sub.example.com/path?token=***", s("https://alice:s3cret@sub.example.com/path?token=abcdef"))
        assertEquals("trojan://***@host:443?sni=a", s("trojan://pw12345@host:443?sni=a"))
        assertEquals("vless://***@1.2.3.4:443?pbk=***&sid=***&sni=x", s("vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?pbk=AbCdEf&sid=1234&sni=x"))
    }

    @Test fun `base64 share links and wireguard keys`() {
        val vmess = "vmess://" + "eyJ2IjoiMiIsInBzIjoiYSIsImFkZCI6IjEuMi4zLjQiLCJwb3J0Ijo0NDMsImlkIjoiYWJjIn0="
        assertEquals("vmess://***", s(vmess))
        val wg = "peer key yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        assertFalse("yAnz5TF" in s(wg)); assertTrue("<key>" in s(wg))
    }

    @Test fun `ordinary text is untouched`() {
        val t = "Default network changed (wifi:100 → cell:101); reconnecting to example.com:443 in 2s"
        assertEquals(t, s(t))
    }
}
