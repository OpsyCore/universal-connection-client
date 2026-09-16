package io.ucc.core.engine.manager

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultConnectionManagerTest {

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = FakeClock()
        val core = FakeCore()
        val host = FakeTunnelHost()
        val network = FakeNetwork()
        val profiles = FakeProfiles(mapOf("p1" to profile("p1"), "p2" to profile("p2")))
        val manager = DefaultConnectionManager(scope, core, host, profiles, network, clock, fastPolicy)
        val seen = mutableListOf<ConnectionState>()

        init {
            scope.launch { manager.transitions.collect { synchronized(seen) { seen += it } } }
        }

        fun close() = scope.cancel()

        fun seenCount(predicate: (ConnectionState) -> Boolean): Int = synchronized(seen) { seen.count(predicate) }

        /** Waits on the non-conflated transition log; `state` may skip transient states on fast machines. */
        suspend fun awaitSeen(timeoutMs: Long = 5_000, predicate: (ConnectionState) -> Boolean): ConnectionState =
            kotlinx.coroutines.withTimeout(timeoutMs) {
                while (true) {
                    synchronized(seen) { seen.firstOrNull(predicate) }?.let { return@withTimeout it }
                    delay(5)
                }
                @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
            }
    }

    private fun withHarness(block: suspend Harness.() -> Unit) = runBlocking {
        val h = Harness()
        try {
            // let reactive collectors subscribe before emitting
            delay(20)
            h.block()
        } finally {
            h.close()
        }
    }

    @Test
    fun `happy path goes Disconnected → Starting → Connecting → Connected`() = withHarness {
        manager.connect("p1")
        val s = manager.state.awaitValue { it is ConnectionState.Connected }
        assertEquals("p1", (s as ConnectionState.Connected).profileId)
        assertEquals(1, core.startCount)
        assertEquals(1, host.acquireCount)
        val names = synchronized(seen) { seen.map { it::class.simpleName } }
        assertEquals(listOf("Starting", "Connecting", "Connected"), names)
    }

    @Test
    fun `unknown profile yields InvalidConfiguration error`() = withHarness {
        manager.connect("missing")
        val s = manager.state.awaitValue { it is ConnectionState.Error }
        assertIs<ConnectionError.InvalidConfiguration>((s as ConnectionState.Error).error)
        assertEquals(0, host.acquireCount)
    }

    @Test
    fun `vpn permission denied surfaces as error and never starts core`() = withHarness {
        host.failWith = ConnectionError.VpnPermissionDenied()
        manager.connect("p1")
        val s = manager.state.awaitValue { it is ConnectionState.Error }
        assertIs<ConnectionError.VpnPermissionDenied>((s as ConnectionState.Error).error)
        assertEquals(0, core.startCount)
    }

    @Test
    fun `core start failure releases tunnel and reports error`() = withHarness {
        core.startError = ConnectionError.InvalidConfiguration("bad json")
        manager.connect("p1")
        val s = manager.state.awaitValue { it is ConnectionState.Error }
        assertIs<ConnectionError.InvalidConfiguration>((s as ConnectionState.Error).error)
        assertEquals(1, host.acquireCount)
        assertEquals(1, host.releaseCount)
    }

    @Test
    fun `probe failures exhaust retries then error`() = withHarness {
        repeat(5) { core.probeScript.addLast(ConnectionError.ConnectionTimeout("t")) }
        manager.connect("p1")
        val s = manager.state.awaitValue { it is ConnectionState.Error }
        assertIs<ConnectionError.ConnectionTimeout>((s as ConnectionState.Error).error)
        assertEquals(1, core.stopCount)
        assertEquals(1, host.releaseCount)
    }

    @Test
    fun `first probe fails second succeeds`() = withHarness {
        core.probeScript.addLast(ConnectionError.ConnectionTimeout("t"))
        core.probeScript.addLast(null)
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
    }

    @Test
    fun `disconnect goes through Stopping to Disconnected and tears everything down`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        manager.disconnect()
        // Wait on the non-conflated transition log: `state` can reach Disconnected before the collector has recorded Stopping.
        awaitSeen { it is ConnectionState.Stopping }
        awaitSeen { it is ConnectionState.Disconnected }
        manager.state.awaitValue { it is ConnectionState.Disconnected }
        assertEquals(1, core.stopCount)
        assertEquals(1, host.releaseCount)
    }

    @Test
    fun `connecting to a different profile switches`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected && it.profileId == "p1" }
        manager.connect("p2")
        manager.state.awaitValue { it is ConnectionState.Connected && it.profileId == "p2" }
        assertEquals(2, core.startCount)
        assertEquals(1, core.stopCount)
    }

    @Test
    fun `connecting to the same profile is a no-op`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        manager.connect("p1")
        delay(50)
        assertEquals(1, core.startCount)
    }

    @Test
    fun `network change while connected triggers reconnect and returns to Connected`() = withHarness {
        network.events.emit(NetworkEvent.DefaultChanged("wifi:1", "wifi"))
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        network.events.emit(NetworkEvent.DefaultChanged("cell:2", "cellular"))
        awaitSeen { it is ConnectionState.Reconnecting }
        val s = awaitSeen { it is ConnectionState.Connected && seenCount { st -> st is ConnectionState.Connected } >= 2 }
        assertIs<ConnectionState.Connected>(s)
        assertEquals(1, core.networkChangedCount)
        assertEquals(1, core.startCount, "network change must not restart the core")
    }

    @Test
    fun `first network report does not trigger a reconnect`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        network.events.emit(NetworkEvent.DefaultChanged("wifi:1", "wifi"))
        delay(50)
        assertEquals(0, core.networkChangedCount)
        assertIs<ConnectionState.Connected>(manager.state.value)
    }

    @Test
    fun `network lost moves to Reconnecting(attempt 0) and a new network recovers`() = withHarness {
        network.events.emit(NetworkEvent.DefaultChanged("wifi:1", "wifi"))
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        network.events.emit(NetworkEvent.Lost)
        val r = awaitSeen { it is ConnectionState.Reconnecting }
        assertEquals(0, (r as ConnectionState.Reconnecting).attempt)
        network.events.emit(NetworkEvent.DefaultChanged("cell:2", "cellular"))
        manager.state.awaitValue { it is ConnectionState.Connected }
        assertEquals(1, core.networkChangedCount)
        assertEquals(1, core.startCount)
    }

    @Test
    fun `retryable core fatal restarts core with backoff and recovers`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        core.events.emit(CoreEvent.Fatal(ConnectionError.CoreFailure("boom")))
        awaitSeen { it is ConnectionState.Reconnecting }
        awaitSeen { it is ConnectionState.Connected && seenCount { st -> st is ConnectionState.Connected } >= 2 }
        assertEquals(2, core.startCount)
    }

    @Test
    fun `non-retryable core fatal ends in Error`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        core.events.emit(CoreEvent.Fatal(ConnectionError.AuthenticationFailure("401")))
        val s = manager.state.awaitValue { it is ConnectionState.Error }
        assertIs<ConnectionError.AuthenticationFailure>((s as ConnectionState.Error).error)
        assertEquals(1, host.releaseCount)
    }

    @Test
    fun `revocation ends in VpnRevoked error`() = withHarness {
        manager.connect("p1")
        manager.state.awaitValue { it is ConnectionState.Connected }
        host.revoked.emit(Unit)
        val s = manager.state.awaitValue { it is ConnectionState.Error }
        assertIs<ConnectionError.VpnRevoked>((s as ConnectionState.Error).error)
    }

    @Test
    fun `attachRunningTunnel restores Connected without starting core`() = withHarness {
        manager.attachRunningTunnel("p1", 123L)
        val s = manager.state.awaitValue { it is ConnectionState.Connected }
        assertEquals(123L, (s as ConnectionState.Connected).sinceEpochMs)
        assertEquals(0, core.startCount)
        yield()
    }

    @Test
    fun `backoff is deterministic and capped`() {
        val p = ReconnectPolicy(backoffBaseMs = 1_000, maxBackoffMs = 15_000)
        assertEquals(1_000, p.backoffFor(1))
        assertEquals(2_000, p.backoffFor(2))
        assertEquals(4_000, p.backoffFor(3))
        assertEquals(8_000, p.backoffFor(4))
        assertEquals(15_000, p.backoffFor(5))
        assertEquals(15_000, p.backoffFor(30))
    }
}
