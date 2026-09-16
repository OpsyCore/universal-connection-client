package io.ucc.app.data.crypto

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AesGcmFileCodecTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val k = key()
    private val codec = AesGcmFileCodec({ k })
    private val plain = """[{"id":"1","name":"secret-name","authentication":{"type":"trojan","password":"hunter2"}}]""".encodeToByteArray()

    @Test fun `round trip`() {
        assertContentEquals(plain, codec.decode(codec.encode(plain)))
    }

    @Test fun `ciphertext contains neither plaintext nor is it deterministic`() {
        val a = codec.encode(plain); val b = codec.encode(plain)
        assertFalse(a.decodeToString(Charsets.ISO_8859_1).contains("hunter2"))
        assertFalse(a.contentEquals(b), "IV must differ per write")
        assertTrue(AesGcmFileCodec.isEncrypted(a)); assertFalse(AesGcmFileCodec.isEncrypted(plain))
    }

    @Test fun `tampering, truncation, wrong key and foreign bytes are all rejected`() {
        val ct = codec.encode(plain)
        val flipped = ct.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(flipped) }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(ct.copyOf(ct.size - 1)) }
        assertFailsWith<FileCodec.CorruptOrForeign> { AesGcmFileCodec({ key() }).decode(ct) }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(plain) }
        assertFailsWith<FileCodec.CorruptOrForeign> { codec.decode(ByteArray(0)) }
    }

    @Test fun `empty payload round trips`() {
        assertContentEquals(ByteArray(0), codec.decode(codec.encode(ByteArray(0))))
    }
}
