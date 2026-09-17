package io.ucc.app.data

import io.ucc.core.config.CapabilityCheck
import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.manager.NetworkEvent
import io.ucc.core.engine.manager.NetworkMonitor
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileSource
import io.ucc.core.smart.ConnectionTestResult
import io.ucc.core.smart.HealthCheckRunner
import io.ucc.core.smart.HealthStatus
import io.ucc.core.smart.InMemoryServerHealthStore
import io.ucc.core.smart.Selection
import io.ucc.core.smart.ServerHealth
import io.ucc.core.smart.ServerHealthEvaluator
import io.ucc.core.smart.SmartFailoverPolicy
import io.ucc.core.smart.SmartServerSelector
import io.ucc.core.smart.TcpConnectionTester
import io.ucc.core.smart.TestFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SmartConnectionCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    /** Explicit scope on the test scheduler: advanced by advanceUntilIdle(), cancelled after each test. */
    private val scope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob())
    @kotlin.test.AfterTest fun tearDown() { scope.cancel() }
    private val store = FakeProfileStore()
    private val health = InMemoryServerHealthStore()
    private val manager = FakeConnectionManager()
    private val selection = FakeSelection()
    private val network = object : NetworkMonitor { val flow = MutableSharedFlow<NetworkEvent>(); override val events: Flow<NetworkEvent> = flow }
    private var now = 1_700_000_000_000L
    private val reachable = HashSet<String>()
    private val dialer = TcpConnectionTester.Dialer { host, _, _ -> if (host in reachable) 30L else throw java.net.SocketTimeoutException() }
    private val runner = HealthCheckRunner(TcpConnectionTester(dialer, dispatcher, timeoutMs = 10), health, { now }, parallelism = 2)

    private fun TestScope.coordinator() = SmartConnectionCoordinator(
        scope = scope, manager = manager, profiles = store, health = health, runner = runner,
        capabilities = CapabilityCheck(testCapabilities), selection = selection, networkMonitor = network,
        failover = SmartFailoverPolicy(SmartServerSelector(), maxSwitchesPerSession = 2), now = { now },
    )

    private suspend fun seed(): List<ConnectionProfile> = testImporter().import(
        "trojan://pw@a.example.com:443#A\ntrojan://pw@b.example.com:443#B\ntrojan://pw@c.example.com:443#C\nhysteria2://pw@h.example.com:443#H",
        ProfileSource.Manual,
    ).profiles.also { store.upsertAll(it) }

    private fun h(p: ConnectionProfile) = health.get(p.fingerprint)

    @Test fun `cold smart connect tests candidates first then connects to the best`() = runTest(dispatcher) {
        val (a, b, c, hy) = seed(); reachable += "b.example.com"
        val co = coordinator(); selection.smartMode = true
        assertIs<Selection.NeedsMeasurement>(co.select())
        co.connectSmart(); advanceUntilIdle()
        assertEquals(listOf(b.id), manager.connected)
        assertEquals(HealthStatus.HEALTHY, ServerHealthEvaluator.status(h(b), now))
        assertEquals(HealthStatus.OFFLINE, ServerHealthEvaluator.status(h(a), now))
        assertEquals(HealthStatus.UNKNOWN, ServerHealthEvaluator.status(h(hy), now), "UDP-only is not punished")
        assertIs<SmartConnectionCoordinator.Phase.Chosen>(co.phase.value)
        assertEquals(c.name, "C")
    }

    @Test fun `smart connect with nothing reachable reports no healthy server and does not connect`() = runTest(dispatcher) {
        seed(); val co = coordinator(); selection.smartMode = true
        co.connectSmart(); advanceUntilIdle()
        assertTrue(manager.connected.isEmpty())
        assertEquals(SmartConnectionCoordinator.Phase.NoHealthyServer, co.phase.value)
        co.dismissPhase(); assertEquals(SmartConnectionCoordinator.Phase.Idle, co.phase.value)
    }

    @Test fun `tunnel outcomes feed health for manual connections too`() = runTest(dispatcher) {
        val (a) = seed(); val co = coordinator()
        co.connectManual(a.id); advanceUntilIdle()
        manager.state.value = ConnectionState.Starting(a.id); manager.state.value = ConnectionState.Connected(a.id, now); advanceUntilIdle()
        assertEquals(1, h(a).successCount)
        manager.state.value = ConnectionState.Error(a.id, ConnectionError.AuthenticationFailure("x")); advanceUntilIdle()
        assertEquals(1, h(a).consecutiveFailures)
        assertEquals(TestFailure.AUTH_FAILURE, h(a).lastFailure)
        assertEquals(listOf(a.id), manager.connected, "manual mode never fails over")
    }

    @Test fun `smart session fails over after terminal error, bounded, never revisits`() = runTest(dispatcher) {
        val (a, b, c) = seed(); reachable += listOf("a.example.com", "b.example.com", "c.example.com")
        val co = coordinator(); selection.smartMode = true
        co.connectSmart(); advanceUntilIdle()
        val first = manager.connected.single()
        manager.state.value = ConnectionState.Starting(first)
        manager.state.value = ConnectionState.Error(first, ConnectionError.ConnectionTimeout("gave up")); advanceUntilIdle()
        assertEquals(2, manager.connected.size)
        assertIs<SmartConnectionCoordinator.Phase.FailingOver>(co.phase.value)
        val second = manager.connected[1]
        manager.state.value = ConnectionState.Starting(second)
        manager.state.value = ConnectionState.Error(second, ConnectionError.ConnectionTimeout("gave up")); advanceUntilIdle()
        assertEquals(3, manager.connected.size)
        val third = manager.connected[2]
        assertEquals(setOf(a.id, b.id, c.id), manager.connected.toSet(), "three distinct servers")
        manager.state.value = ConnectionState.Starting(third)
        manager.state.value = ConnectionState.Error(third, ConnectionError.ConnectionTimeout("gave up")); advanceUntilIdle()
        assertEquals(3, manager.connected.size, "switch limit reached: no infinite loop")
        assertEquals(SmartConnectionCoordinator.Phase.NoHealthyServer, co.phase.value)
    }

    @Test fun `non-server errors do not trigger failover`() = runTest(dispatcher) {
        seed(); reachable += listOf("a.example.com", "b.example.com")
        val co = coordinator(); selection.smartMode = true
        co.connectSmart(); advanceUntilIdle()
        val first = manager.connected.single()
        manager.state.value = ConnectionState.Error(first, ConnectionError.VpnPermissionDenied()); advanceUntilIdle()
        assertEquals(1, manager.connected.size)
    }

    @Test fun `user disconnect ends the smart session`() = runTest(dispatcher) {
        seed(); reachable += listOf("a.example.com", "b.example.com")
        val co = coordinator(); selection.smartMode = true
        co.connectSmart(); advanceUntilIdle()
        val first = manager.connected.single()
        co.disconnect(); advanceUntilIdle()
        manager.state.value = ConnectionState.Error(first, ConnectionError.ConnectionTimeout("late")); advanceUntilIdle()
        assertEquals(1, manager.connected.size)
    }

    @Test fun `health of deleted profiles is pruned but renamed and re-imported ones keep history`() = runTest(dispatcher) {
        val (a, b) = seed(); reachable += "a.example.com"
        coordinator(); advanceUntilIdle()
        runner.test(a); runner.test(b); advanceUntilIdle()
        assertEquals(2, health.all.value.size)
        // rename keeps fingerprint → history intact
        store.upsertAll(listOf(a.copy(name = "A renamed"))); advanceUntilIdle()
        assertEquals(30L, h(a).latencyMs)
        // subscription-style replace: same content under a new id keeps history; b removed → pruned
        store.apply(listOf(a.copy(id = "new-id")), listOf(a.id, b.id)); advanceUntilIdle()
        assertEquals(1, health.all.value.size)
        assertEquals(30L, health.get(a.fingerprint).latencyMs)
        assertEquals(ServerHealth.EMPTY, health.get(b.fingerprint))
    }

    @Test fun `empty profile list does not wipe history`() = runTest(dispatcher) {
        val (a) = seed(); reachable += "a.example.com"
        coordinator(); advanceUntilIdle()
        runner.test(a); advanceUntilIdle()
        store.deleteAll(store.current().map { it.id }); advanceUntilIdle()
        assertEquals(1, health.all.value.size, "empty list is indistinguishable from not-yet-loaded; keep")
    }

    @Test fun `network change marks evidence unknown without erasing it`() = runTest(dispatcher) {
        val (a) = seed(); reachable += "a.example.com"
        val co = coordinator(); advanceUntilIdle()
        network.flow.emit(NetworkEvent.DefaultChanged("wifi:wlan0:1", "wifi")); advanceUntilIdle()
        runner.test(a); advanceUntilIdle()
        assertEquals("wifi", h(a).lastNetworkTransport)
        assertIs<Selection.Chosen>(co.select())
        network.flow.emit(NetworkEvent.DefaultChanged("cellular:rmnet0:2", "cellular")); advanceUntilIdle()
        assertIs<Selection.NeedsMeasurement>(co.select())
        assertEquals(30L, h(a).latencyMs, "history retained")
        assertEquals("cellular", co.transport.value)
    }

    @Test fun `stale evidence forces a re-test`() = runTest(dispatcher) {
        val (a) = seed(); reachable += "a.example.com"
        val co = coordinator(); advanceUntilIdle()
        runner.test(a); advanceUntilIdle()
        assertIs<Selection.Chosen>(co.select())
        now += ServerHealthEvaluator.STALE_AFTER_MS + 1
        assertIs<Selection.NeedsMeasurement>(co.select())
    }

    @Test fun `unsupported profiles are excluded from smart candidates`() = runTest(dispatcher) {
        val ps = testImporter().import("hysteria2://pw@h.example.com:443#H", ProfileSource.Manual).profiles
        store.upsertAll(ps)
        val co = coordinator(); advanceUntilIdle()
        assertEquals(Selection.NoCandidates, co.select(), "hysteria2 is not in testCapabilities → excluded")
        assertEquals(TestFailure.UNSUPPORTED, ConnectionTestResult.failed(TestFailure.UNSUPPORTED).failure)
    }
}
