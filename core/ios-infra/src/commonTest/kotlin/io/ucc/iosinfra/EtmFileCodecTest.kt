package io.ucc.iosinfra

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Framing tests with a deterministic *fake* cipher (XOR stream + simple keyed
 * checksum). They verify layout, header authentication and tamper detection of the
 * envelope; the real primitives are covered by [DarwinCrypto] on device and by
 * `JvmCryptoPrimitivesTest` (javax.crypto) on the JVM.
 */
internal object FakePrimitives : CryptoPrimitives {
    private const val PAD: Byte = 0x5A // stands in for the PKCS#7 padding check
    override fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray) = byteArrayOf(PAD) + plain.mapIndexed { i, b -> (b.toInt() xor key[i % 32].toInt() xor iv[i % 16].toInt()).toByte() }.toByteArray()
    override fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray {
        if (cipher.isEmpty() || cipher[0] != PAD) throw IllegalArgumentException("padding")
        return cipher.drop(1).mapIndexed { i, b -> (b.toInt() xor key[i % 32].toInt() xor iv[i % 16].toInt()).toByte() }.toByteArray()
    }
    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(32); data.forEachIndexed { i, b -> out[i % 32] = (out[i % 32].toInt() xor b.toInt() xor key[i % key.size].toInt()).toByte() }; return out
    }
    override fun randomBytes(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }
}

class EtmFileCodecTest {
    private val key = ByteArray(64) { (it + 1).toByte() }
    private val codec = EtmFileCodec(FakePrimitives, { key })

    @Test fun layout_is_magic_ivlen_iv_ct_tag() {
        val out = codec.encode("abc".encodeToByteArray())
        assertContentEquals("UCC2".encodeToByteArray(), out.copyOfRange(0, 4))
        assertEquals(16, out[4].toInt())
        assertEquals(4 + 1 + 16 + 4 + 32, out.size)
        assertTrue(EtmFileCodec.isEncrypted(out))
    }

    @Test fun round_trip_including_empty() {
        for (p in listOf("", "x", "{\"a\":1}")) assertContentEquals(p.encodeToByteArray(), codec.decode(codec.encode(p.encodeToByteArray())))
    }

    @Test fun tampering_anywhere_is_rejected() {
        val out = codec.encode("payload".encodeToByteArray())
        for (i in out.indices) {
            val t = out.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertFailsWith<FileCodec.CorruptOrForeign>("byte $i") { codec.decode(t) }
        }
    }

    @Test fun truncated_and_foreign_inputs_are_rejected() {
        val out = codec.encode("payload".encodeToByteArray())
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(out.copyOf(out.size - 1)) }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(byteArrayOf()) }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode("UCC1".encodeToByteArray() + ByteArray(60)) }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode("[]".encodeToByteArray()) }
    }

    @Test fun wrong_key_is_rejected() {
        val out = codec.encode("payload".encodeToByteArray())
        val other = EtmFileCodec(FakePrimitives, { ByteArray(64) { 9 } })
        assertFailsWith<FileCodec.CorruptOrForeign> { other.decode(out) }
    }

    @Test fun wrong_key_length_is_a_programming_error() {
        assertFailsWith<IllegalArgumentException> { EtmFileCodec(FakePrimitives, { ByteArray(32) }).encode(byteArrayOf()) }
    }

    @Test fun constant_time_equals() {
        assertTrue(EtmFileCodec.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertTrue(!EtmFileCodec.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertTrue(!EtmFileCodec.constantTimeEquals(byteArrayOf(1), byteArrayOf(1, 2)))
    }
}
