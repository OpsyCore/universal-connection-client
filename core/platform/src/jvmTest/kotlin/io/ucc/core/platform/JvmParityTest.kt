package io.ucc.core.platform

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the multiplatform primitives are byte-for-byte equivalent to the JDK
 * calls the core modules used before the KMP migration.
 */
class JvmParityTest {
    private val samples = listOf(
        "", "plain", "a b+c", "سلام دنیا", "日本語", "😀 emoji", "~.-_*", "/?#[]@!$&'()*+,;=", "100%", "%41%42", "%D8%B3+x",
        "vless://uuid@host:443?type=ws&path=%2Fws%3Fx%3D1&host=a.b#name%20with%20space", "\u0000\u001f\u007f",
    ) + List(50) { String(CharArray(Random.nextInt(0, 40)) { Random.nextInt(0x20, 0x7ff).toChar() }) }

    @Test
    fun encodeMatchesUrlEncoderVariant() {
        for (s in samples) {
            val expected = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")
            assertEquals(expected, UrlCodec.encode(s), "encode($s)")
        }
    }

    @Test
    fun decodeMatchesUrlDecoderWithPlusProtected() {
        for (s in samples) {
            val input = s.replace("+", "%2B")
            val expected = runCatching { URLDecoder.decode(input, Charsets.UTF_8) }
            val actual = runCatching { UrlCodec.decode(input) }
            assertEquals(expected.isSuccess, actual.isSuccess, "decode($input) success")
            if (expected.isSuccess) assertEquals(expected.getOrThrow(), actual.getOrThrow(), "decode($input)")
        }
        // decode(encode(x)) round trips
        for (s in samples) assertEquals(s, UrlCodec.decode(UrlCodec.encode(s)))
    }

    @Test
    fun base64MatchesJavaUtil() {
        repeat(200) {
            val bytes = Random.nextBytes(Random.nextInt(0, 70))
            assertEquals(Base64.getEncoder().encodeToString(bytes), Base64Codec.encode(bytes))
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), Base64Codec.encodeUrlSafeNoPadding(bytes))
            assertEquals(bytes.toList(), Base64Codec.decodeStrict(Base64.getEncoder().encodeToString(bytes)).toList())
        }
        for (bad in listOf("abc", "a", "ab==x", "aGVs bG8=", "aGVsbG8")) {
            val j = runCatching { Base64.getDecoder().decode(bad) }.isSuccess
            val k = runCatching { Base64Codec.decodeStrict(bad) }.isSuccess
            assertEquals(j, k, "decodeStrict($bad)")
        }
    }

    @Test
    fun sha256MatchesMessageDigest() {
        repeat(50) {
            val bytes = Random.nextBytes(Random.nextInt(0, 300))
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, sha256(bytes).toHexLower())
        }
    }
}
