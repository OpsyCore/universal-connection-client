package io.ucc.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Apple-specific parity checks (the shared vectors live in commonTest and also
 * run here). NOTE: these can only execute on a macOS host; Linux CI compiles
 * them (compile verification) but cannot run them.
 */
class IosPrimitivesTest {
    @Test
    fun sha256MillionA() {
        // FIPS 180-2 vector: one million 'a'.
        val digest = sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).toHexLower()
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", digest)
    }

    @Test
    fun sha256MultiBlockBoundary() {
        // 56-byte message (padding crosses a block boundary).
        val msg = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", sha256(msg).toHexLower())
    }

    @Test
    fun uuidIsLowerCaseCanonicalV4() {
        repeat(50) {
            val id = randomUuidString()
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
        }
    }

    @Test
    fun clockIsEpochMillis() {
        val now = currentTimeMillis()
        assertTrue(now > 1_700_000_000_000L && now < 4_102_444_800_000L, "not epoch-milliseconds: $now")
    }
}
