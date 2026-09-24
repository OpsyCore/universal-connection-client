package io.ucc.iosinfra

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Codec that rejects anything it did not write — enough to drive the quarantine path. */
private object TaggingCodec : FileCodec {
    override fun encode(plain: ByteArray) = byteArrayOf(7) + plain
    override fun decode(stored: ByteArray) = if (stored.firstOrNull() == 7.toByte()) stored.copyOfRange(1, stored.size) else throw FileCodec.CorruptOrForeign("tag")
}

class SecureFileTest {
    @Test fun missing_when_no_file() {
        val f = SecureFile(InMemoryBlobStore(), "profiles", PlainCodec)
        assertFalse(f.exists()); assertIs<SecureFile.ReadResult.Missing>(f.read())
    }

    @Test fun round_trip_uses_enc_suffix() {
        val fs = InMemoryBlobStore(); val f = SecureFile(fs, "profiles", TaggingCodec)
        f.write("hello".encodeToByteArray())
        assertEquals(setOf("profiles.enc"), fs.files.keys)
        assertContentEquals("hello".encodeToByteArray(), (f.read() as SecureFile.ReadResult.Ok).bytes)
    }

    @Test fun corrupt_file_is_quarantined_not_deleted() {
        val fs = InMemoryBlobStore(); fs.files["profiles.enc"] = byteArrayOf(1, 2, 3)
        val r = SecureFile(fs, "profiles", TaggingCodec).read()
        assertIs<SecureFile.ReadResult.Unreadable>(r)
        assertTrue(r.quarantinedName.startsWith("profiles.enc.corrupt-"))
        assertFalse(fs.exists("profiles.enc")); assertTrue(fs.exists(r.quarantinedName))
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read(r.quarantinedName))
    }

    @Test fun write_failure_propagates() {
        val fs = InMemoryBlobStore().apply { failNextWrite = true }
        assertFailsWith<BlobIoException> { SecureFile(fs, "x", PlainCodec).write(byteArrayOf()) }
        assertTrue(fs.files.isEmpty())
    }
}
