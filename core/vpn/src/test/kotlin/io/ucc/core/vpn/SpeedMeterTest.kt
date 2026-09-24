package io.ucc.core.vpn

import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedMeterTest {
    private val stats = CoreStatistics(
        uplinkBytesPerSecond = 84 * 1024L, downlinkBytesPerSecond = (1.2 * 1024 * 1024).toLong(),
        uplinkTotalBytes = 1, downlinkTotalBytes = 1, connectionsIn = 0, connectionsOut = 0, memoryBytes = 0, goroutines = 0,
    )
    private val connected = ConnectionState.Connected(profileId = "p", sinceEpochMs = 0L)

    @Test fun `renders only when enabled and really connected and stats exist`() {
        assertTrue(SpeedMeter.shouldRender(enabled = true, state = connected, stats = stats))
        assertFalse(SpeedMeter.shouldRender(enabled = false, state = connected, stats = stats), "default OFF hides the meter")
        assertFalse(SpeedMeter.shouldRender(enabled = true, state = connected, stats = null), "no core statistics → nothing to show")
        assertFalse(SpeedMeter.shouldRender(enabled = true, state = ConnectionState.Disconnected, stats = stats))
        assertFalse(SpeedMeter.shouldRender(enabled = true, state = ConnectionState.Starting("p"), stats = stats))
        assertFalse(SpeedMeter.shouldRender(enabled = true, state = ConnectionState.Stopping("p"), stats = stats))
    }

    @Test fun `line formats down then up with locale-independent units`() {
        assertEquals("\u2193 1.2 MB/s  \u2191 84.0 KB/s", SpeedMeter.line(stats))
        assertEquals("0 B/s", SpeedMeter.rate(0))
        assertEquals("0 B/s", SpeedMeter.rate(-5), "negative rates are clamped, never shown")
        assertEquals("1023 B/s", SpeedMeter.rate(1023))
        assertEquals("100 KB/s", SpeedMeter.rate(100 * 1024L))
        assertEquals("2.0 GB/s", SpeedMeter.rate(2L * 1024 * 1024 * 1024))
    }
}
