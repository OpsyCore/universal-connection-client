package io.ucc.core.smart

import io.ucc.core.model.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmartServerSelectorTest {
    private val sel = SmartServerSelector()
    private fun chosen(vararg c: Candidate): String = assertIs<Selection.Chosen>(sel.select(c.toList(), NOW)).candidate.profile.id

    @Test fun `empty list`() { assertEquals(Selection.NoCandidates, sel.select(emptyList(), NOW)) }

    @Test fun `one supported server with data is chosen`() { assertEquals("a", chosen(cand("a", healthy(300)))) }

    @Test fun `one server without data needs measurement`() {
        val r = assertIs<Selection.NeedsMeasurement>(sel.select(listOf(cand("a")), NOW))
        assertEquals(listOf("a"), r.toTest.map { it.profile.id })
    }

    @Test fun `unsupported servers are never chosen or tested`() {
        assertEquals(Selection.NoCandidates, sel.select(listOf(cand("u", healthy(1), supported = false)), NOW))
        assertEquals("b", chosen(cand("u", healthy(1), supported = false), cand("b", healthy(500))))
        val r = assertIs<Selection.NeedsMeasurement>(sel.select(listOf(cand("u", supported = false), cand("x")), NOW))
        assertEquals(listOf("x"), r.toTest.map { it.profile.id })
    }

    @Test fun `healthy beats degraded beats unknown beats offline`() {
        assertEquals("h", chosen(cand("o", failing(3)), cand("u"), cand("d", failing(1)), cand("h", healthy(900))))
        assertEquals("d", chosen(cand("o", failing(3)), cand("u"), cand("d", failing(1))))
        assertEquals("u", chosen(cand("o", failing(3)), cand("u")))
    }

    @Test fun `lower latency preferred among equally healthy`() {
        assertEquals("fast", chosen(cand("slow", healthy(400)), cand("fast", healthy(40)), cand("mid", healthy(120))))
    }

    @Test fun `stable server preferred over unstable within a latency band`() {
        val stable = healthy(59, successes = 10, failures = 0)
        val flaky = healthy(51, successes = 6, failures = 6) // same 50 ms band, worse availability
        assertEquals("stable", chosen(cand("flaky", flaky), cand("stable", stable)))
    }

    @Test fun `latency band prevents noise-level differences from beating stability`() {
        val a = healthy(145, successes = 4, failures = 4)
        val b = healthy(149, successes = 10, failures = 0)
        assertEquals("b", chosen(cand("a", a), cand("b", b)))
        // but a genuinely faster server (different band) still wins even if less stable
        assertEquals("c", chosen(cand("b", b), cand("c", healthy(20, successes = 4, failures = 4))))
    }

    @Test fun `stale healthy data is treated as unknown, fresh data wins`() {
        val stale = healthy(10, at = NOW - ServerHealthEvaluator.STALE_AFTER_MS - 1)
        assertEquals("fresh", chosen(cand("stale", stale), cand("fresh", healthy(300))))
    }

    @Test fun `all stale means measurement, best history first, bounded`() {
        val sel3 = SmartServerSelector(maxToTest = 3)
        val cs = (1..10).map { i -> cand("s$i", healthy(10, at = NOW - ServerHealthEvaluator.STALE_AFTER_MS - i * MIN)) } + cand("never")
        val r = assertIs<Selection.NeedsMeasurement>(sel3.select(cs, NOW))
        assertEquals(listOf("s1", "s2", "s3"), r.toTest.map { it.profile.id })
    }

    @Test fun `evidence from another transport is unknown but history is kept`() {
        val onWifi = healthy(30).copy(lastNetworkTransport = "wifi")
        assertEquals(HealthStatus.UNKNOWN, ServerHealthEvaluator.status(onWifi, NOW, currentTransport = "cellular"))
        assertEquals(HealthStatus.HEALTHY, ServerHealthEvaluator.status(onWifi, NOW, currentTransport = "wifi"))
        assertEquals(HealthStatus.HEALTHY, ServerHealthEvaluator.status(onWifi, NOW, currentTransport = null))
        val r = sel.select(listOf(cand("a", onWifi)), NOW, currentTransport = "cellular")
        assertIs<Selection.NeedsMeasurement>(r)
        assertEquals(30L, r.toTest.single().health.latencyMs, "history intact")
    }

    @Test fun `all offline is reported, not chosen`() {
        val r = assertIs<Selection.AllUnhealthy>(sel.select(listOf(cand("a", failing(3)), cand("b", failing(5))), NOW))
        assertEquals(listOf("a", "b"), r.ranked.map { it.profile.id })
    }

    @Test fun `deterministic tie-break by id`() {
        val cs = listOf(cand("z", healthy(50)), cand("a", healthy(50)), cand("m", healthy(50)))
        repeat(3) { assertEquals("a", chosen(*cs.shuffled().toTypedArray())) }
    }

    @Test fun `ranking is exposed best first`() {
        val r = assertIs<Selection.Chosen>(sel.select(listOf(cand("c", failing(3)), cand("b", healthy(200)), cand("a", healthy(20))), NOW))
        assertEquals(listOf("a", "b", "c"), r.ranked.map { it.profile.id })
    }

    @Test fun `nextAfter skips current, excluded and offline`() {
        val ranked = listOf(cand("a", healthy(20)), cand("b", healthy(30)), cand("c", failing(3)), cand("d", healthy(40)))
        assertEquals("b", sel.nextAfter(ranked, "a", NOW, emptySet())?.profile?.id)
        assertEquals("d", sel.nextAfter(ranked, "a", NOW, setOf("b"))?.profile?.id)
        assertEquals(null, sel.nextAfter(ranked, "a", NOW, setOf("b", "d")))
    }

    @Test fun `udp-only protocol with no data is still a measurement candidate but unknown after test`() {
        // The TCP tester will report UNSUPPORTED, which is non-attributable → stays UNKNOWN, never OFFLINE.
        val h = ServerHealth.EMPTY.record(ConnectionTestResult.failed(TestFailure.UNSUPPORTED), NOW, null)
        assertEquals(HealthStatus.UNKNOWN, ServerHealthEvaluator.status(h, NOW))
        assertTrue(cand("hy", h, protocol = Protocol.HYSTERIA2).supported)
    }
}
