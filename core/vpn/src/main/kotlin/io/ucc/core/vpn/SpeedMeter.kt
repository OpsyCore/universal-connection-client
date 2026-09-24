package io.ucc.core.vpn

import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreStatistics
import java.util.Locale

/**
 * Pure helpers for the optional "speed in notification" feature. No Android types, so
 * they are unit-tested on the JVM. The service only renders rates when [shouldRender]
 * says so: switch ON *and* really [ConnectionState.Connected]; any other state (starting,
 * reconnecting, stopping, disconnected) hides the meter so the notification can never
 * claim traffic while the tunnel is not actually up.
 */
public object SpeedMeter {
    public fun shouldRender(enabled: Boolean, state: ConnectionState, stats: CoreStatistics?): Boolean =
        enabled && state is ConnectionState.Connected && stats != null

    /** `↓ 1.2 MB/s  ↑ 84 KB/s` — locale-independent digits (notification is not a place for RTL numerals). */
    public fun line(stats: CoreStatistics): String =
        "\u2193 ${rate(stats.downlinkBytesPerSecond)}  \u2191 ${rate(stats.uplinkBytesPerSecond)}"

    public fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond.coerceAtLeast(0)) + "/s"

    internal fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format(Locale.ROOT, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }
}
