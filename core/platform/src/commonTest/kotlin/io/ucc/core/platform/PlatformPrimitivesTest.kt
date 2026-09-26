package io.ucc.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformPrimitivesTest {
    @Test
    fun sha256KnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(ByteArray(0)).toHexLower())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()).toHexLower())
    }

    @Test
    fun base64Semantics() {
        assertEquals("aGVsbG8=", Base64Codec.encode("hello".encodeToByteArray()))
        assertEquals("aGVsbG8", Base64Codec.encodeUrlSafeNoPadding("hello".encodeToByteArray()))
        assertEquals("_-8", Base64Codec.encodeUrlSafeNoPadding(byteArrayOf(0xff.toByte(), 0xef.toByte())))
        assertEquals("hello", Base64Codec.decodeStrict("aGVsbG8=").decodeToString())
        assertEquals("hello", Base64Codec.decodeStrict("aGVsbG8").decodeToString())   // padding optional, as on the JVM
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeStrict("a") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeStrict("aGVsbG8=x") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decodeStrict("aGVs*G8=") }
    }

    @Test
    fun urlEncodeMatchesRfc3986Subset() {
        assertEquals("a%20b%2Bc~d.e-f_g*h", UrlCodec.encode("a b+c~d.e-f_g*h"))
        assertEquals("%D8%B3%D9%84%D8%A7%D9%85", UrlCodec.encode("سلام"))
        assertEquals("%2F%3F%23%40%3A%3D%26", UrlCodec.encode("/?#@:=&"))
    }

    @Test
    fun urlDecodeSemantics() {
        assertEquals("a b+c", UrlCodec.decode("a%20b+c"))               // '+' preserved literally
        assertEquals("سلام", UrlCodec.decode("%D8%B3%D9%84%D8%A7%D9%85"))
        assertEquals("x", UrlCodec.decode("x"))
        assertEquals("\uFFFD", UrlCodec.decode("%FF"))                    // malformed UTF-8 → replacement char
        assertFailsWith<IllegalArgumentException> { UrlCodec.decode("abc%2") }
        assertFailsWith<IllegalArgumentException> { UrlCodec.decode("abc%zz") }
    }

    @Test
    fun idsAndClock() {
        val id = randomUuidString()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id), id)
        assertTrue(currentTimeMillis() > 1_600_000_000_000L)
    }
}
