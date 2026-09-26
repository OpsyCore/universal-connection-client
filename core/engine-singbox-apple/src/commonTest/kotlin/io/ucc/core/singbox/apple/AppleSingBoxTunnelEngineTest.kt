package io.ucc.core.singbox.apple

import io.ucc.core.singbox.apple.AppleSingBoxTunnelEngine.State
import io.ucc.iosvpn.ProviderState
import io.ucc.iosvpn.TunnelSession
import io.ucc.iosvpn.VpnError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleSingBoxTunnelEngineTest {
    @Test fun start_then_stop_then_start_again() = runTest {
        val lb = FakeLibbox(); val host = FakeTunnelHost(); val e = engine(lb, host)
        assertEquals(State.IDLE, e.state.value)
        e.start(startRequest()); assertEquals(State.RUNNING, e.state.value); assertEquals(1, host.acquired)
        assertNull(e.statistics())
        lb.listener!!.onStatus(LibboxStatus(0, 0, 10, 20, 0, 0, 0, 0)); assertEquals(10L to 20L, e.statistics())
        e.stop(); assertEquals(State.STOPPED, e.state.value); assertEquals(1, host.released)
        assertNull(e.statistics(), "no statistics while stopped")
        e.stop() // idempotent
        e.start(startRequest()); assertEquals(State.RUNNING, e.state.value)
        assertEquals(1, lb.services, "single core instance across restarts")
    }

    @Test fun duplicate_start_is_rejected_and_does_not_touch_core() = runTest {
        val lb = FakeLibbox(); val e = engine(lb)
        e.start(startRequest())
        val calls = lb.calls.size
        val err = assertFailsWith<VpnError.ProviderStartupFailure> { e.start(startRequest()) }
        assertTrue(err.detail.contains("already running")); assertEquals(calls, lb.calls.size)
        assertEquals(State.RUNNING, e.state.value)
    }

    @Test fun missing_libbox_fails_start_cleans_up_and_never_runs() = runTest {
        val lb = FakeLibbox(available = false); val host = FakeTunnelHost(); val e = engine(lb, host)
        val err = assertFailsWith<VpnError.ProviderStartupFailure> { e.start(startRequest()) }
        assertTrue(err.detail.contains("Libbox unavailable")); assertFalse(err.detail.contains(SECRET))
        assertEquals(State.STOPPED, e.state.value); assertEquals(1, host.released)
        // and through the provider-side session: FAILED, never RUNNING
        val session = TunnelSession(e, testClock)
        val r = session.start(startRequest()) {}
        assertIs<VpnError.ProviderStartupFailure>(r.exceptionOrNull()); assertEquals(ProviderState.FAILED, session.state.value)
    }

    @Test fun unknown_profile_is_invalid_configuration() = runTest {
        val e = engine(FakeLibbox(), profiles = FakeProfiles())
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { e.start(startRequest("nope")) }
        assertEquals(State.STOPPED, e.state.value)
    }

    @Test fun core_start_failure_propagates_classified_and_redacted() = runTest {
        val lb = FakeLibbox().apply { failStart = IllegalStateException("auth failed password=$SECRET") }
        val host = FakeTunnelHost(); val e = engine(lb, host)
        val err = assertFailsWith<VpnError.ProviderStartupFailure> { e.start(startRequest()) }
        assertTrue(err.detail.startsWith("AuthenticationFailure")); assertFalse(err.detail.contains(SECRET))
        assertEquals(1, host.released); assertEquals(State.STOPPED, e.state.value)
    }

    @Test fun stop_during_start_cancels_and_cleans_up() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lb = FakeLibbox(); val host = FakeTunnelHost()
        val profiles = object : io.ucc.core.engine.manager.ProfileProvider {
            override suspend fun byId(id: String) = gate.await().let { profile(id) }
        }
        val e = engine(lb, host, profiles)
        val starting = async { runCatching { e.start(startRequest()) } }
        yield(); assertEquals(State.STARTING, e.state.value)
        e.stop() // only flags; start() finishes the cleanup
        assertEquals(State.STARTING, e.state.value)
        gate.complete(Unit)
        val r = starting.await()
        assertIs<VpnError.ProviderStartupFailure>(r.exceptionOrNull())
        assertEquals(State.STOPPED, e.state.value)
        assertFalse(lb.calls.contains("start"), "core never started after cancellation"); assertEquals(1, host.released)
    }

    @Test fun shutdown_failure_is_reported_but_state_settles() = runTest {
        val lb = FakeLibbox(); val host = FakeTunnelHost(); val e = engine(lb, host)
        e.start(startRequest()); lb.failClose = IllegalStateException("x")
        val err = assertFailsWith<VpnError.ProviderShutdownFailure> { e.stop() }
        assertEquals("CoreFailure", err.detail); assertEquals(State.STOPPED, e.state.value); assertEquals(1, host.released)
    }

    @Test fun terminate_releases_core_and_blocks_restart() = runTest {
        val lb = FakeLibbox(); val e = engine(lb)
        e.start(startRequest()); e.terminate(); e.terminate()
        assertEquals(State.TERMINATED, e.state.value)
        assertEquals(listOf("closeService", "close"), lb.calls.takeLast(2))
        assertEquals(1, lb.calls.count { it == "close" })
        assertFailsWith<VpnError.ProviderStartupFailure> { e.start(startRequest()) }
    }

    @Test fun session_full_lifecycle_through_engine() = runTest {
        val lb = FakeLibbox(); val e = engine(lb); val s = TunnelSession(e, testClock)
        assertTrue(s.start(startRequest()) {}.isSuccess); assertEquals(ProviderState.RUNNING, s.state.value)
        assertTrue(s.stop(TunnelSession.StopReason.USER).isSuccess); assertEquals(ProviderState.STOPPED, s.state.value)
        assertEquals(State.STOPPED, e.state.value)
    }

    @Test fun configuration_handoff_uses_request_mtu() = runTest {
        val lb = FakeLibbox(); val e = engine(lb)
        e.start(startRequest()); assertTrue(lb.lastConfig!!.contains("\"mtu\":1500"))
    }
}
