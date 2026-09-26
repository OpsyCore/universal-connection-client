package io.ucc.app.ui

import android.content.Context
import io.ucc.app.R
import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import java.util.Locale

fun ConnectionState.label(context: Context): String = when (this) {
    ConnectionState.Disconnected -> context.getString(R.string.state_disconnected)
    is ConnectionState.Starting -> context.getString(R.string.state_starting)
    is ConnectionState.Connecting -> context.getString(R.string.state_connecting)
    is ConnectionState.Connected -> context.getString(R.string.state_connected)
    is ConnectionState.Reconnecting -> context.getString(R.string.state_reconnecting, attempt)
    is ConnectionState.Stopping -> context.getString(R.string.state_stopping)
    is ConnectionState.Error -> context.getString(R.string.state_error)
}

fun ConnectionError.userMessage(context: Context): String {
    val id = context.resources.getIdentifier(userMessageKey, "string", context.packageName)
    return if (id != 0) context.getString(id) else context.getString(R.string.error_unknown)
}

/** Short, actionable next step for an error; null when there is nothing useful to add. */
fun ConnectionError.userHint(context: Context): String? {
    val id = context.resources.getIdentifier(userMessageKey + "_hint", "string", context.packageName)
    return if (id != 0) context.getString(id) else null
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
}

fun formatRate(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s) else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
