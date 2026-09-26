package io.ucc.app.data.crypto

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Atomic, codec-wrapped file. `name.enc` is the live file; writes go to a temp
 * file first and are renamed into place. Reads that fail to decode are moved
 * aside as `name.enc.corrupt-<ts>` (never silently deleted) and reported.
 *
 * Also owns the one-time migration from the Phase-1 plaintext `name.json`:
 * on first [read], if only the plaintext exists it is encoded, written as
 * `name.enc`, then overwritten with random bytes and deleted.
 */
class SecureFile(dir: File, private val name: String, private val codec: FileCodec) {
    private val live = File(dir, "$name.enc")
    private val tmp = File(dir, "$name.enc.tmp")
    private val legacyPlain = File(dir, "$name.json")
    private val legacyTmp = File(dir, "$name.json.tmp")

    sealed class ReadResult {
        data object Missing : ReadResult()
        data class Ok(val bytes: ByteArray, val migratedFromPlaintext: Boolean) : ReadResult()
        /** Live file exists but cannot be decoded (key changed / tampered / truncated). Moved to [quarantined]. */
        data class Unreadable(val quarantined: File) : ReadResult()
    }

    fun exists(): Boolean = live.exists() || legacyPlain.exists()

    @Throws(IOException::class)
    fun read(): ReadResult {
        legacyTmp.delete()
        if (!live.exists() && legacyPlain.exists()) {
            val plain = legacyPlain.readBytes()
            write(plain)
            shred(legacyPlain)
            return ReadResult.Ok(plain, migratedFromPlaintext = true)
        }
        if (!live.exists()) return ReadResult.Missing
        val stored = live.readBytes()
        return try {
            ReadResult.Ok(codec.decode(stored), migratedFromPlaintext = false)
        } catch (e: FileCodec.CorruptOrForeign) {
            val q = File(live.parentFile, "${live.name}.corrupt-${System.currentTimeMillis()}")
            if (!live.renameTo(q)) live.delete()
            ReadResult.Unreadable(q)
        }
    }

    @Throws(IOException::class)
    fun write(plain: ByteArray) {
        tmp.writeBytes(codec.encode(plain))
        if (!tmp.renameTo(live)) {
            live.delete()
            if (!tmp.renameTo(live)) throw IOException("cannot replace ${live.name}")
        }
        // A successful encrypted write supersedes any plaintext left over from Phase 1.
        if (legacyPlain.exists()) shred(legacyPlain)
    }

    /** Best-effort overwrite before delete: not a guarantee on flash storage, but better than leaving the bytes intact. */
    private fun shred(f: File) {
        try {
            val len = f.length()
            if (len > 0) {
                val junk = ByteArray(minOf(len, 1L shl 20).toInt()).also { SecureRandom().nextBytes(it) }
                f.outputStream().use { out -> var left = len; while (left > 0) { val n = minOf(left, junk.size.toLong()).toInt(); out.write(junk, 0, n); left -= n }; out.fd.sync() }
            }
        } catch (_: IOException) {
        } finally {
            f.delete()
        }
    }
}
