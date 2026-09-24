package io.ucc.iosinfra

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** javax.crypto stand-in for [DarwinCrypto] so the envelope is exercised with real AES/HMAC on Linux CI. */
internal object JvmPrimitives : CryptoPrimitives {
    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    override fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain)
    override fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray =
        try { cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(cipher) } catch (e: java.security.GeneralSecurityException) { throw IllegalArgumentException(e) }
    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

class JvmCryptoPrimitivesTest {
    private val key = ByteArray(64).also { SecureRandom().nextBytes(it) }
    private val codec = EtmFileCodec(JvmPrimitives, { key })

    @Test fun real_aes_hmac_round_trip_and_sizes() {
        for (n in listOf(0, 1, 15, 16, 17, 1000)) {
            val plain = ByteArray(n) { it.toByte() }
            val out = codec.encode(plain)
            assertEquals(4 + 1 + 16 + ((n / 16) + 1) * 16 + 32, out.size)
            assertContentEquals(plain, codec.decode(out))
        }
    }

    @Test fun ciphertexts_differ_per_write_but_decode_equal() {
        val a = codec.encode("same".encodeToByteArray()); val b = codec.encode("same".encodeToByteArray())
        assert(!a.contentEquals(b))
        assertContentEquals(codec.decode(a), codec.decode(b))
    }

    @Test fun known_answer_hmac_and_aes() {
        // RFC 4231 test case 2
        val mac = JvmPrimitives.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.joinToString("") { "%02x".format(it) })
        // NIST SP 800-38A F.2.5 CBC-AES256 block 1
        val k = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexBytes()
        val iv = "000102030405060708090a0b0c0d0e0f".hexBytes()
        val ct = JvmPrimitives.aesCbcEncrypt(k, iv, "6bc1bee22e409f96e93d7e117393172a".hexBytes())
        assertEquals("f58c4c04d6e5f1ba779eabfb5f7bfbd6", ct.copyOf(16).joinToString("") { "%02x".format(it) })
    }

    @Test fun tampered_tag_fails_before_decrypt() {
        val out = codec.encode("secret".encodeToByteArray()); out[out.size - 1] = (out[out.size - 1].toInt() xor 1).toByte()
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(out) }
    }

    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
