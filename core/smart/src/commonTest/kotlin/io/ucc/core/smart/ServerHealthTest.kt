package io.ucc.core.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerHealthTest {
    @Test fun `successful result records latency and resets streak`() {
        val h = ServerHealth.EMPTY
            .record(ConnectionTestResult.failed(TestFailure.TIMEOUT), NOW - 2 * MIN, "wifi")
            .record(ConnectionTestResult.failed(TestFailure.TIMEOUT), NOW - MIN, "wifi")
            .record(ConnectionTestResult.ok(42), NOW, "wifi")
        assertEquals(42L, h.latencyMs)
        assertEquals(42L, h.rollingLatencyMs)
        assertEquals(1, h.latencySampleCount)
        assertEquals(0, h.consecutiveFailures)
        assertEquals(NOW, h.lastSuccessAtEpochMs)
        assertEquals(1, h.successCount)
        assertEquals(2, h.failureCount)
        assertEquals("wifi", h.lastNetworkTransport)
    }

    @Test fun `failed result increments streak and keeps last failure class`() {
        val h = healthy(50).record(ConnectionTestResult.failed(TestFailure.CONNECTION_REFUSED), NOW, null)
        assertEquals(1, h.consecutiveFailures)
        assertEquals(TestFailure.CONNECTION_REFUSED, h.lastFailure)
        assertEquals(50L, h.latencyMs, "latency of the last success is retained for display")
        assertEquals(NOW, h.lastCheckedAtEpochMs)
    }

    @Test fun `rolling latency is an EMA and not the last sample`() {
        val h = ServerHealth.EMPTY.record(ConnectionTestResult.ok(100), NOW, null).record(ConnectionTestResult.ok(200), NOW + 1, null)
        assertEquals(200L, h.latencyMs)
        assertEquals(130L, h.rollingLatencyMs) // 100*0.7 + 200*0.3
        assertEquals(2, h.latencySampleCount)
    }

    @Test fun `network-unavailable and cancelled do not count against the server`() {
        val h = healthy(50)
            .record(ConnectionTestResult.failed(TestFailure.NETWORK_UNAVAILABLE), NOW, "cellular")
            .record(ConnectionTestResult.failed(TestFailure.CANCELLED), NOW + 1, "cellular")
            .record(ConnectionTestResult.failed(TestFailure.UNSUPPORTED), NOW + 2, "cellular")
        assertEquals(0, h.consecutiveFailures)
        assertEquals(0, h.failureCount)
        assertEquals(TestFailure.UNSUPPORTED, h.lastFailure, "still remembered for display")
        assertEquals(HealthStatus.HEALTHY, ServerHealthEvaluator.status(h, NOW + 3))
    }

    @Test fun `availability needs a minimum sample size`() {
        var h = ServerHealth.EMPTY
        assertNull(h.availability)
        h = h.record(ConnectionTestResult.ok(10), NOW, null).record(ConnectionTestResult.ok(10), NOW, null)
        assertNull(h.availability, "2 samples is not enough")
        h = h.record(ConnectionTestResult.failed(TestFailure.TIMEOUT), NOW, null)
        assertEquals(2.0 / 3, h.availability!!, 1e-9)
    }

    @Test fun `counters are bounded so availability reflects the recent window`() {
        var h = ServerHealth.EMPTY
        repeat(40) { h = h.record(ConnectionTestResult.ok(10), NOW, null) }
        assertEquals(ServerHealth.MAX_COUNTED, h.successCount)
        repeat(10) { h = h.record(ConnectionTestResult.failed(TestFailure.TIMEOUT), NOW, null) }
        assertEquals(ServerHealth.MAX_COUNTED, h.successCount + h.failureCount)
        assertEquals(0.5, h.availability!!, 1e-9)
    }

    @Test fun `tunnel-up success without latency keeps previous latency sample`() {
        val h = healthy(70).record(ConnectionTestResult.TUNNEL_UP, NOW, "wifi")
        assertEquals(70L, h.latencyMs)
        assertEquals(5, h.latencySampleCount)
        assertEquals(NOW, h.lastSuccessAtEpochMs)
        assertEquals(6, h.successCount)
    }

    @Test fun `stale data is UNKNOWN regardless of content`() {
        val old = healthy(20, at = NOW - ServerHealthEvaluator.STALE_AFTER_MS - 1)
        assertEquals(HealthStatus.UNKNOWN, ServerHealthEvaluator.status(old, NOW))
        assertTrue(ServerHealthEvaluator.isStale(old, NOW))
        assertTrue(ServerHealthEvaluator.isStale(ServerHealth.EMPTY, NOW))
        assertEquals(HealthStatus.HEALTHY, ServerHealthEvaluator.status(healthy(20), NOW))
    }

    @Test fun `status thresholds`() {
        assertEquals(HealthStatus.DEGRADED, ServerHealthEvaluator.status(failing(1), NOW))
        assertEquals(HealthStatus.DEGRADED, ServerHealthEvaluator.status(failing(2), NOW))
        assertEquals(HealthStatus.OFFLINE, ServerHealthEvaluator.status(failing(3), NOW))
        assertEquals(HealthStatus.OFFLINE, ServerHealthEvaluator.status(failing(1, lastSuccess = null), NOW), "never worked + failed = offline")
        assertEquals(HealthStatus.UNKNOWN, ServerHealthEvaluator.status(ServerHealth.EMPTY, NOW))
    }

    @Test fun `result invariants are enforced`() {
        assertFailsWith<IllegalArgumentException> { ConnectionTestResult(success = true, latencyMs = 1, failure = TestFailure.TIMEOUT) }
        assertFailsWith<IllegalArgumentException> { ConnectionTestResult(success = false, latencyMs = 5, failure = TestFailure.TIMEOUT) }
        assertNotNull(ConnectionTestResult.ok(1))
    }

    @Test fun `serialised record contains no profile data`() {
        val json = kotlinx.serialization.json.Json.encodeToString(ServerHealth.serializer(), healthy(42))
        assertTrue("example" !in json && "Server" !in json && "443" !in json, json)
    }
}
