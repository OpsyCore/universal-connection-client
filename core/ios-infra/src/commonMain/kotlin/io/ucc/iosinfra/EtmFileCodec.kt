package io.ucc.iosinfra

/**
 * Cryptographic primitives the envelope needs. iOS: CommonCrypto (`CCCrypt`,
 * `CCHmac`, `SecRandomCopyBytes`); JVM tests: javax.crypto. Nothing here is
 * implemented by hand — this is only the seam.
 */
public interface CryptoPrimitives {
    /** AES-256-CBC with PKCS#7 padding. [key] is 32 bytes, [iv] 16 bytes. */
    public fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray
    /** @throws IllegalArgumentException on bad padding / length. */
    public fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray
    public fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    public fun randomBytes(n: Int): ByteArray
}

/**
 * Whole-file encryption for Apple targets, **encrypt-then-MAC**:
 *
 * `"UCC2"` magic · 1 byte IV length (16) · IV · AES-256-CBC ciphertext · HMAC-SHA-256 tag (32 bytes)
 *
 * The tag covers magic ‖ ivLen ‖ IV ‖ ciphertext, so the header is authenticated
 * exactly like the AAD in the Android AES-GCM envelope (`"UCC1"`). A distinct
 * magic guarantees the two envelopes are never confused. GCM itself is not used
 * because CommonCrypto exposes no public GCM API and CryptoKit is Swift-only.
 *
 * [keys] is a 64-byte secret (32 encryption + 32 MAC) supplied by a provider so
 * production reads it from the Keychain and tests use an in-memory value.
 */
public class EtmFileCodec(
    private val primitives: CryptoPrimitives,
    private val keys: () -> ByteArray,
    private val magic: ByteArray = MAGIC,
) : FileCodec {

    override fun encode(plain: ByteArray): ByteArray {
        val (enc, mac) = split(keys())
        val iv = primitives.randomBytes(IV_BYTES)
        val ct = primitives.aesCbcEncrypt(enc, iv, plain)
        val header = magic + byteArrayOf(IV_BYTES.toByte()) + iv
        val tag = primitives.hmacSha256(mac, header + ct)
        return header + ct + tag
    }

    override fun decode(stored: ByteArray): ByteArray {
        if (stored.size < magic.size + 1 || !stored.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw FileCodec.CorruptOrForeign("missing magic")
        }
        val ivLen = stored[magic.size].toInt() and 0xff
        val ivStart = magic.size + 1
        if (ivLen != IV_BYTES || stored.size < ivStart + ivLen + TAG_BYTES) throw FileCodec.CorruptOrForeign("bad header")
        val (enc, mac) = split(keys())
        val body = stored.copyOfRange(0, stored.size - TAG_BYTES)
        val tag = stored.copyOfRange(stored.size - TAG_BYTES, stored.size)
        if (!constantTimeEquals(primitives.hmacSha256(mac, body), tag)) throw FileCodec.CorruptOrForeign("mac mismatch")
        val iv = stored.copyOfRange(ivStart, ivStart + ivLen)
        val ct = stored.copyOfRange(ivStart + ivLen, stored.size - TAG_BYTES)
        return try {
            primitives.aesCbcDecrypt(enc, iv, ct)
        } catch (e: IllegalArgumentException) {
            throw FileCodec.CorruptOrForeign("decrypt", e)
        }
    }

    private fun split(k: ByteArray): Pair<ByteArray, ByteArray> {
        require(k.size == KEY_BYTES) { "expected $KEY_BYTES key bytes" }
        return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
    }

    public companion object {
        public val MAGIC: ByteArray = "UCC2".encodeToByteArray()
        public const val KEY_BYTES: Int = 64
        private const val IV_BYTES = 16
        private const val TAG_BYTES = 32

        public fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

        internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}
