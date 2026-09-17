package io.ucc.core.smart

import io.ucc.core.model.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HealthCheckRunnerTest {

    private class FakeTester(val delayMs: Long = 100, val result: (String) -> ConnectionTestResult = { ConnectionTestResult.ok(50) }) : ConnectionTester {
        val active = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        override suspend fun test(profile: io.ucc.core.model.ConnectionProfile): ConnectionTestResult {
            calls.incrementAndGet()
            val a = active.incrementAndGet(); peak.updateAndGet { maxOf(it, a) }
            try { delay(delayMs) } finally { active.decrementAndGet() }
            return result(profile.id)
        }
    }

    @Test fun `parallelism is bounded`() = runTest {
        val t = FakeTester()
        val runner = HealthCheckRunner(t, InMemoryServerHealthStore(), { NOW }, parallelism = 3)
        runner.testAll((1..10).map { profile("p$it") })
        assertEquals(3, t.peak.get())
        assertEquals(10, t.calls.get())
    }

    @Test fun `results are recorded by fingerprint and duplicates share a record`() = runTest {
        val store = InMemoryServerHealthStore()
        val runner = HealthCheckRunner(FakeTester(result = { if (it == "bad") ConnectionTestResult.failed(TestFailure.TIMEOUT) else ConnectionTestResult.ok(30) }), store, { NOW })
        val p1 = profile("good"); val dup = p1.copy(id = "good-copy", name = "renamed")
        runner.testAll(listOf(p1, dup, profile("bad")))
        assertEquals(p1.fingerprint, dup.fingerprint)
        assertEquals(2, store.all.value.size)
        assertEquals(2, store.get(p1.fingerprint).successCount)
        assertEquals(1, store.get(profile("bad").fingerprint).consecutiveFailures)
    }

    @Test fun `same profile is not tested twice concurrently`() = runTest {
        val t = FakeTester(delayMs = 500)
        val runner = HealthCheckRunner(t, InMemoryServerHealthStore(), { NOW })
        val p = profile("p")
        val a = async { runner.test(p) }; val b = async { runner.test(p) }
        advanceUntilIdle()
        assertEquals(a.await(), b.await())
        assertEquals(1, t.calls.get())
    }

    @Test fun `cancellation stops pending tests and records CANCELLED without punishing the server`() = runTest {
        val t = FakeTester(delayMs = 1_000)
        val store = InMemoryServerHealthStore()
        val runner = HealthCheckRunner(t, store, { NOW }, parallelism = 2)
        val ps = (1..6).map { profile("p$it") }
        val job = launch { runner.testAll(ps) }
        advanceTimeBy(100); job.cancel(); advanceUntilIdle()
        assertTrue(t.calls.get() <= 2)
        assertTrue(store.all.value.values.all { it.consecutiveFailures == 0 })
        assertTrue(store.all.value.values.all { it.lastFailure == TestFailure.CANCELLED })
    }

    @Test fun `tcp tester classifies failures and skips udp protocols`() = runTest {
        fun tester(throwing: Throwable?) = TcpConnectionTester(dialer = { _, _, _ -> if (throwing != null) throw throwing else 12L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals(ConnectionTestResult.ok(12), tester(null).test(profile("a")))
        assertEquals(TestFailure.TIMEOUT, tester(SocketTimeoutException()).test(profile("a")).failure)
        assertEquals(TestFailure.DNS_FAILURE, tester(UnknownHostException("h")).test(profile("a")).failure)
        assertEquals(TestFailure.CONNECTION_REFUSED, tester(ConnectException("Connection refused")).test(profile("a")).failure)
        assertEquals(TestFailure.NETWORK_UNAVAILABLE, tester(ConnectException("Network is unreachable")).test(profile("a")).failure)
        assertEquals(TestFailure.UNKNOWN, tester(IllegalStateException("secret-uuid-1234")).test(profile("a")).failure)
        assertEquals(TestFailure.UNSUPPORTED, tester(null).test(profile("h", Protocol.HYSTERIA2)).failure)
        assertEquals(TestFailure.UNSUPPORTED, tester(null).test(profile("w", Protocol.WIREGUARD)).failure)
    }

    @Test fun `results never carry host, port or exception text`() = runTest {
        val r = TcpConnectionTester(dialer = { _, _, _ -> throw IllegalStateException("password=hunter2 host=1.2.3.4") }, io = kotlinx.coroutines.Dispatchers.Unconfined).test(profile("a"))
        assertEquals(ConnectionTestResult.failed(TestFailure.UNKNOWN), r)
        assertTrue("hunter2" !in r.toString() && "1.2.3.4" !in r.toString())
    }

    @Test fun `in-flight map is cleaned up after completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val t = object : ConnectionTester { override suspend fun test(profile: io.ucc.core.model.ConnectionProfile): ConnectionTestResult { gate.await(); return ConnectionTestResult.ok(1) } }
        val runner = HealthCheckRunner(t, InMemoryServerHealthStore(), { NOW })
        val p = profile("p")
        val first = async { runner.test(p) }
        gate.complete(Unit); first.await()
        // A second, later test must run again (not reuse the finished deferred).
        val t2 = FakeTester(delayMs = 1)
        val runner2 = HealthCheckRunner(t2, InMemoryServerHealthStore(), { NOW })
        runner2.test(p); runner2.test(p)
        assertEquals(2, t2.calls.get())
    }
}
