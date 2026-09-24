package io.ucc.core.smart

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreDelayProbe
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.DelayProbeSession
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpDelayConnectionTesterTest {

    /** Records sessions; `delays[id]` = ms, or a ConnectionError to throw. */
    private class FakeProbe(val delays: Map<String, Any>) : CoreDelayProbe {
        val sessions = ArrayList<List<String>>()
        var lastOptions: CoreStartOptions? = null
        val measured = ArrayList<Pair<String, String>>()
        override fun supports(profile: ConnectionProfile) = profile.protocol != Protocol.WIREGUARD
        override suspend fun <T> open(profiles: List<ConnectionProfile>, options: CoreStartOptions, block: suspend (DelayProbeSession) -> T): T {
            sessions += profiles.map { it.id }; lastOptions = options
            return block(object : DelayProbeSession {
                override suspend fun measure(profileId: String, url: String, timeoutMs: Long): Long {
                    measured += profileId to url
                    return when (val d = delays[profileId]) {
                        is Long -> d
                        is Int -> d.toLong()
                        is ConnectionError -> throw CoreException(d)
                        else -> throw CoreException(ConnectionError.CoreFailure("not in session"))
                    }
                }
            })
        }
    }

    private val opts = CoreStartOptions(tlsFragment = true)

    @Test fun `testAll boots one session for all hosted profiles and uses the tunnel start options`() = runTest {
        val probe = FakeProbe(mapOf("a" to 120, "b" to ConnectionError.ConnectionTimeout("t"), "c" to 80))
        val tester = HttpDelayConnectionTester(probe, { opts }, url = { "https://cp.example/generate_204" }, fallback = null)
        val store = InMemoryServerHealthStore()
        val runner = HealthCheckRunner(tester, store, { NOW })
        val results = HashMap<String, ConnectionTestResult>()
        runner.testAll(listOf(profile("a"), profile("b"), profile("c"))) { p, r -> results[p.id] = r }
        assertEquals(1, probe.sessions.size)
        assertEquals(listOf("a", "b", "c"), probe.sessions[0])
        assertEquals(opts, probe.lastOptions)
        assertTrue(probe.measured.all { it.second == "https://cp.example/generate_204" })
        assertEquals(ConnectionTestResult.ok(120), results["a"])
        assertEquals(ConnectionTestResult.failed(TestFailure.TIMEOUT), results["b"])
        assertEquals(80L, store.get(profile("c").fingerprint).latencyMs)
        assertEquals(1, store.get(profile("b").fingerprint).consecutiveFailures)
    }

    @Test fun `single test opens its own session`() = runTest {
        val probe = FakeProbe(mapOf("a" to 55))
        val tester = HttpDelayConnectionTester(probe, { opts }, fallback = null)
        assertEquals(ConnectionTestResult.ok(55), tester.test(profile("a")))
        assertEquals(listOf(listOf("a")), probe.sessions)
    }

    @Test fun `unsupported profiles go to the fallback tester and are excluded from the session`() = runTest {
        val probe = FakeProbe(mapOf("a" to 40))
        val fallbackCalls = ArrayList<String>()
        val fallback = ConnectionTester { p -> fallbackCalls += p.id; ConnectionTestResult.failed(TestFailure.UNSUPPORTED) }
        val tester = HttpDelayConnectionTester(probe, { opts }, fallback = fallback)
        val runner = HealthCheckRunner(tester, InMemoryServerHealthStore(), { NOW })
        val out = HashMap<String, ConnectionTestResult>()
        runner.testAll(listOf(profile("a"), profile("wg", Protocol.WIREGUARD))) { p, r -> out[p.id] = r }
        assertEquals(listOf(listOf("a")), probe.sessions)
        assertEquals(listOf("wg"), fallbackCalls)
        assertEquals(TestFailure.UNSUPPORTED, out["wg"]!!.failure)
        assertEquals(40L, out["a"]!!.latencyMs)
    }

    @Test fun `probe failures are classified and never throw out of test`() = runTest {
        val probe = FakeProbe(mapOf(
            "dns" to ConnectionError.DnsFailure("d"), "tls" to ConnectionError.TlsFailure("t"), "auth" to ConnectionError.AuthenticationFailure("a"),
            "net" to ConnectionError.NetworkUnavailable(), "core" to ConnectionError.CoreFailure("c"),
        ))
        val tester = HttpDelayConnectionTester(probe, { opts }, fallback = null)
        assertEquals(TestFailure.DNS_FAILURE, tester.test(profile("dns")).failure)
        assertEquals(TestFailure.TLS_FAILURE, tester.test(profile("tls")).failure)
        assertEquals(TestFailure.AUTH_FAILURE, tester.test(profile("auth")).failure)
        assertEquals(TestFailure.NETWORK_UNAVAILABLE, tester.test(profile("net")).failure)
        assertEquals(TestFailure.UNKNOWN, tester.test(profile("core")).failure)
    }

    @Test fun `test url validation`() {
        assertTrue(HttpDelayConnectionTester.isValidTestUrl("https://www.gstatic.com/generate_204"))
        assertTrue(HttpDelayConnectionTester.isValidTestUrl("http://cp.cloudflare.com/"))
        assertTrue(HttpDelayConnectionTester.isValidTestUrl("https://1.1.1.1:8443/x"))
        assertFalse(HttpDelayConnectionTester.isValidTestUrl("gstatic.com/generate_204"))
        assertFalse(HttpDelayConnectionTester.isValidTestUrl("https://"))
        assertFalse(HttpDelayConnectionTester.isValidTestUrl("https://bad host/"))
        assertFalse(HttpDelayConnectionTester.isValidTestUrl("ftp://x.y"))
    }
}
