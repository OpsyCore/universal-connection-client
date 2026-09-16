package io.ucc.app.data.crypto

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecureFileTest {
    private val dir: File = Files.createTempDirectory("securefile").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val codec = AesGcmFileCodec({ key })
    private val sf = SecureFile(dir, "profiles", codec)

    @Test fun `missing file`() { assertIs<SecureFile.ReadResult.Missing>(sf.read()); assertFalse(sf.exists()) }

    @Test fun `write then read, on-disk bytes are encrypted and no tmp remains`() {
        sf.write("hello".encodeToByteArray())
        val ok = assertIs<SecureFile.ReadResult.Ok>(sf.read())
        assertContentEquals("hello".encodeToByteArray(), ok.bytes)
        assertFalse(ok.migratedFromPlaintext)
        assertTrue(AesGcmFileCodec.isEncrypted(File(dir, "profiles.enc").readBytes()))
        assertFalse(File(dir, "profiles.enc.tmp").exists())
    }

    @Test fun `phase-1 plaintext is migrated once and shredded`() {
        val legacy = File(dir, "profiles.json").apply { writeText("[{\"password\":\"hunter2\"}]") }
        val ok = assertIs<SecureFile.ReadResult.Ok>(sf.read())
        assertTrue(ok.migratedFromPlaintext)
        assertContentEquals("[{\"password\":\"hunter2\"}]".encodeToByteArray(), ok.bytes)
        assertFalse(legacy.exists())
        assertTrue(File(dir, "profiles.enc").exists())
        val again = assertIs<SecureFile.ReadResult.Ok>(sf.read())
        assertFalse(again.migratedFromPlaintext)
    }

    @Test fun `encrypted file wins over stale plaintext, which gets removed on next write`() {
        sf.write("new".encodeToByteArray())
        File(dir, "profiles.json").writeText("old")
        assertContentEquals("new".encodeToByteArray(), assertIs<SecureFile.ReadResult.Ok>(sf.read()).bytes)
        sf.write("newer".encodeToByteArray())
        assertFalse(File(dir, "profiles.json").exists())
    }

    @Test fun `undecryptable file is quarantined, not deleted, and subsequent reads are Missing`() {
        sf.write("data".encodeToByteArray())
        val other = SecureFile(dir, "profiles", AesGcmFileCodec({ KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }))
        val bad = assertIs<SecureFile.ReadResult.Unreadable>(other.read())
        assertTrue(bad.quarantined.exists() && bad.quarantined.name.startsWith("profiles.enc.corrupt-"))
        assertIs<SecureFile.ReadResult.Missing>(other.read())
    }
}
