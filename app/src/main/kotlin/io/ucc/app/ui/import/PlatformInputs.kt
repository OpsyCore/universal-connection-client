package io.ucc.app.ui.import

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Android input adapters. Pure I/O; no parsing. */
object PlatformInputs {
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** Returns clipboard text or null. Never logs the content. */
    fun clipboardText(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        return (item.text ?: item.coerceToText(context))?.toString()?.takeIf { it.isNotBlank() }
    }

    sealed class FileRead {
        data class Ok(val name: String, val content: String) : FileRead()
        data class TooLarge(val limit: Long) : FileRead()
        data class Failed(val reason: String) : FileRead()
    }

    /** Reads a document-picker Uri as UTF-8 text with a hard size cap. */
    suspend fun readDocument(context: Context, uri: Uri): FileRead = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx) && c.getLong(sizeIdx) > MAX_FILE_BYTES) return@withContext FileRead.TooLarge(MAX_FILE_BYTES)
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) c.getString(nameIdx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        try {
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) return@withContext FileRead.TooLarge(MAX_FILE_BYTES)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return@withContext FileRead.Failed("no stream")
            FileRead.Ok(name, String(bytes, Charsets.UTF_8))
        } catch (e: IOException) {
            FileRead.Failed(e.javaClass.simpleName)
        } catch (e: SecurityException) {
            FileRead.Failed("permission")
        }
    }
}
