package io.ucc.core.singbox.android

import android.content.Context
import android.util.Log
import io.ucc.core.singbox.SingBoxConfigGenerator
import java.io.File
import java.security.MessageDigest

/**
 * Installs the bundled binary rule-sets (`assets/rulesets/*.srs`, snapshot of
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
                val bytes = app.assets.open("$ASSET_DIR/$file").use { it.readBytes() }
                val target = File(dir, file)
                if (target.isFile && target.length() == bytes.size.toLong() && sha256(target.readBytes()) == sha256(bytes)) continue
                val tmp = File(dir, "$file.tmp")
                tmp.outputStream().use { it.write(bytes); it.fd.sync() }
                if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "rename $file" } }
                Log.i(TAG, "installed $file (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "rule-set $file unavailable: ${e.javaClass.simpleName}")
            }
        }
        return dir
    }

    /** True when every bundled rule-set is present on disk (checked before generating a config that needs them). */
    fun allPresent(context: Context): Boolean {
        val dir = directory(context)
        return SingBoxConfigGenerator.RULE_SET_FILES.values.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
