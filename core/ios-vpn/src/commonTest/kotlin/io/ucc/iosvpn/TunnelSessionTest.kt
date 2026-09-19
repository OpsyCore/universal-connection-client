package io.ucc.iosvpn

import io.ucc.core.engine.manager.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingEngine(var failStart: Throwable? = null, var failStop: Throwable? = null) : TunnelEngine {
    var started = 0; var stopped = 0; var gate: CompletableDeferred<Unit>? = null
    override suspend fun start(request: TunnelSession.StartRequest) { gate?.await(); failStart?.let { throw it }; started++ }
    override suspend fun stop() { stopped++; failStop?.let { throw it } }
    override fun statistics(): Pair<Long, Long>? = if (started > 0) 10L to 20L else null
}

private val clock = object : Clock { var now = 1_000L; override fun nowMs() = now }
private fun providerConfig(profile: String = "p1", cfg: TunnelConfiguration = sampleConfig()): Map<String, Any?> = ProviderConfigKeys.build(profile, cfg)

class TunnelSessionTest {
    @Test fun parse_valid_and_invalid_provider_configuration() {
        val s = TunnelSession(RecordingEngine(), clock)
        val req = s.parse(providerConfig())
        assertEquals("p1", req.profileId); assertEquals(sampleConfig(), req.tunnelConfiguration)
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { s.parse(null) }
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { s.parse(emptyMap()) }
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { s.parse(providerConfig() + (ProviderConfigKeys.SCHEMA_VERSION to "2")) }
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { s.parse(providerConfig() + (ProviderConfigKeys.PROFILE_ID to "")) }
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { s.parse(providerConfig() + (ProviderConfigKeys.TUNNEL_CONFIG to "{}")) }
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { s.parse(providerConfig() - ProviderConfigKeys.TUNNEL_CONFIG) }
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { ProviderConfigKeys.build("p", sampleConfig().copy(mtu = 1)) }
    }

    @Test fun successful_lifecycle() = runTest {
        val engine = RecordingEngine(); val s = TunnelSession(engine, clock)
        val states = mutableListOf(s.state.value)
        var applied: NetworkSettingsSpec? = null
        assertTrue(s.start(s.parse(providerConfig())) { applied = it; states += s.state.value }.isSuccess)
        assertNotNull(applied); assertEquals(9000, applied!!.mtu)
        assertEquals(ProviderState.RUNNING, s.state.value); assertEquals(1, engine.started)
        val st = IpcCodec.decodeResponse(s.handle(IpcCodec.encodeRequest(IpcRequest.Status))) as IpcResponse.Status
        assertEquals(ProviderState.RUNNING, st.state); assertEquals("p1", st.profileId); assertEquals(1_000L, st.sinceEpochMs); assertNull(st.lastErrorCode)
        assertEquals(IpcResponse.Statistics(true, 10, 20), IpcCodec.decodeResponse(s.handle(IpcCodec.encodeRequest(IpcRequest.Statistics))))
        assertTrue(s.stop(TunnelSession.StopReason.USER).isSuccess)
        assertEquals(ProviderState.STOPPED, s.state.value); assertEquals(1, engine.stopped)
        assertEquals(listOf(ProviderState.IDLE, ProviderState.STARTING), states)
    }

    @Test fun no_engine_never_reports_running() = runTest {
        val s = TunnelSession(TunnelEngine.None, clock)
        val r = s.start(s.parse(providerConfig())) {}
        val e = assertIs<VpnError.ProviderStartupFailure>(r.exceptionOrNull())
        assertTrue(e.detail.contains("no tunnel engine"))
        assertEquals(ProviderState.FAILED, s.state.value)
        val st = IpcCodec.decodeResponse(s.handle(IpcCodec.encodeRequest(IpcRequest.Status))) as IpcResponse.Status
        assertEquals("ProviderStartupFailure", st.lastErrorCode)
    }

    @Test fun settings_failure_and_engine_failure_are_typed_and_cleaned_up() = runTest {
        val e1 = RecordingEngine(); val s1 = TunnelSession(e1, clock)
        val r1 = s1.start(s1.parse(providerConfig())) { throw VpnError.ProviderStartupFailure("setTunnelNetworkSettings failed") }
        assertIs<VpnError.ProviderStartupFailure>(r1.exceptionOrNull()); assertEquals(0, e1.started); assertEquals(ProviderState.FAILED, s1.state.value)

        val e2 = RecordingEngine(failStart = IllegalStateException("libbox exploded: secret=abc")); val s2 = TunnelSession(e2, clock)
        val r2 = s2.start(s2.parse(providerConfig())) {}
        val err = assertIs<VpnError.ProviderStartupFailure>(r2.exceptionOrNull())
        assertFalse(err.detail.contains("secret")); assertEquals(1, e2.stopped)
    }

    @Test fun double_start_rejected_and_restart_after_stop_allowed() = runTest {
        val s = TunnelSession(RecordingEngine(), clock)
        assertTrue(s.start(s.parse(providerConfig())) {}.isSuccess)
        assertIs<VpnError.ProviderStartupFailure>(s.start(s.parse(providerConfig())) {}.exceptionOrNull())
        s.stop(TunnelSession.StopReason.USER)
        assertTrue(s.start(s.parse(providerConfig())) {}.isSuccess)
    }

    @Test fun stop_during_start_cancels_startup() = runTest {
        val engine = RecordingEngine().apply { gate = CompletableDeferred() }; val s = TunnelSession(engine, clock)
        val start = async { s.start(s.parse(providerConfig())) {} }
        yield(); assertEquals(ProviderState.STARTING, s.state.value)
        assertTrue(s.stop(TunnelSession.StopReason.PROVIDER_DISABLED).isSuccess)
        engine.gate!!.complete(Unit)
        val r = start.await()
        assertIs<VpnError.ProviderStartupFailure>(r.exceptionOrNull())
        assertTrue(engine.stopped >= 1); assertEquals(ProviderState.FAILED, s.state.value)
    }

    @Test fun shutdown_failure_is_reported_but_state_is_stopped() = runTest {
        val s = TunnelSession(RecordingEngine(failStop = IllegalStateException("hang")), clock)
        s.start(s.parse(providerConfig())) {}
        val r = s.stop(TunnelSession.StopReason.USER)
        assertIs<VpnError.ProviderShutdownFailure>(r.exceptionOrNull()); assertEquals(ProviderState.STOPPED, s.state.value)
    }

    @Test fun ipc_handler_is_total() = runTest {
        val s = TunnelSession(RecordingEngine(), clock)
        val err = IpcCodec.decodeResponse(s.handle(byteArrayOf(1) + "garbage".encodeToByteArray())) as IpcResponse.Error
        assertEquals("MalformedResponse", err.code)
        assertEquals(IpcResponse.Pong(9), IpcCodec.decodeResponse(s.handle(IpcCodec.encodeRequest(IpcRequest.Ping(9)))))
        assertEquals(IpcResponse.Statistics(false), IpcCodec.decodeResponse(s.handle(IpcCodec.encodeRequest(IpcRequest.Statistics))))
        s.start(s.parse(providerConfig())) {}
        assertEquals(IpcResponse.Ack, IpcCodec.decodeResponse(s.handle(IpcCodec.encodeRequest(IpcRequest.Stop))))
        assertEquals(ProviderState.STOPPED, s.state.value)
    }
}
