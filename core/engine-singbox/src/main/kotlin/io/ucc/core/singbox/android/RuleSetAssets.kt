package io.ucc.core.singbox.android

import android.content.Context
import android.util.Log
import io.ucc.core.singbox.SingBoxConfigGenerator
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs the bundled binary rule-sets (`assets/rulesets/` `.srs` files, snapshot of
 * Chocolate4U/Iran-sing-box-rules — see THIRD_PARTY_NOTICES.md) into private
 * storage so sing-box can load them as `type: local`. Idempotent and cheap:
 * a file is rewritten only when its SHA-256 differs from the asset's. Writes
 * are atomic (temp file + rename) so a killed process never leaves a torn
 * `.srs` for the core to choke on. No network access — updates ship with the app.
 */
internal object RuleSetAssets {
    private const val TAG = "RuleSets"
    private const val ASSET_DIR = "rulesets"

    fun directory(context: Context): File = File(context.applicationContext.filesDir, "singbox/rulesets")

    /** Copies missing/changed rule-set files; returns the directory. Safe to call on every start. */
    @Synchronized
    fun install(context: Context): File {
        val app = context.applicationContext
        val dir = directory(app).apply { mkdirs() }
        for (file in SingBoxConfigGenerator.RULE_SET_FILES.values) {
            try {
                val target = File(dir, file)
                val tmp = File(dir, "$file.tmp")
                // v1.0.4: streamed — the asset is hashed while being copied to the temp file and the installed
                // file is hashed from its stream, so no rule-set is ever held in memory as a whole (fixed 64 KiB buffer).
                val (assetDigest, size) = tmp.outputStream().use { out -> copyAndDigest(app.assets.open("$ASSET_DIR/$file"), out) }
                if (target.isFile && target.length() == size && digestOf(target) == assetDigest) { tmp.delete(); continue }
                if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "rename $file" } }
                Log.i(TAG, "installed $file ($size bytes)")
            } catch (e: Exception) {
                File(dir, "$file.tmp").delete()
                Log.w(TAG, "rule-set $file unavailable: ${e.javaClass.simpleName}")
            }
        }
        return dir
    }

    /** Streams [input] into [out] (fsync'd), returning the SHA-256 hex of the bytes and their count. Closes [input]. */
    private fun copyAndDigest(input: InputStream, out: FileOutputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buf = ByteArray(BUFFER)
        input.use { i ->
            while (true) {
                val n = i.read(buf); if (n < 0) break
                md.update(buf, 0, n); out.write(buf, 0, n); total += n
            }
        }
        out.flush(); out.fd.sync()
        return md.digest().toHex() to total
    }

    private fun digestOf(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        file.inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().toHex()
    }

    private const val BUFFER = 64 * 1024
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** True when every bundled rule-set is present on disk (checked before generating a config that needs them). */
    fun allPresent(context: Context): Boolean {
        val dir = directory(context)
        return SingBoxConfigGenerator.RULE_SET_FILES.values.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

}
