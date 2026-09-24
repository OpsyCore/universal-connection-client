package io.ucc.app.ui.servers

import io.ucc.app.testing.FakeConnectionManager
import io.ucc.app.testing.FakeFetcher
import io.ucc.app.testing.FakeProfileStore
import io.ucc.app.testing.FakeSelection
import io.ucc.app.testing.FakeSubscriptionStore
import io.ucc.applogic.ServerRepository
import io.ucc.applogic.SubscriptionRefresher
import io.ucc.app.testing.testImporter
import io.ucc.core.smart.ConnectionTestResult
import io.ucc.core.smart.HealthCheckRunner
import io.ucc.core.smart.HealthStatus
import io.ucc.core.smart.InMemoryServerHealthStore
import io.ucc.core.smart.TcpConnectionTester
import io.ucc.core.smart.TestFailure
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.engine.ConnectionState
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val store = FakeProfileStore()
    private val subs = FakeSubscriptionStore()
    private val manager = FakeConnectionManager()
    private val selection = FakeSelection()
    private val fetcher = FakeFetcher()
    private val repo = ServerRepository(store, subs, manager, now = { 5L })
    private val refresher = SubscriptionRefresher(testImporter(), fetcher, store, subs, manager, now = { 5L }, parseDispatcher = dispatcher)
    private val importer = testImporter()

    private val dialer = TcpConnectionTester.Dialer { host, _, _ -> if (host.startsWith("1.2.3")) 25L else throw TcpConnectionTester.DialException(io.ucc.core.smart.TestFailure.TIMEOUT) }
    private val tester = TcpConnectionTester(dialer, dispatcher, timeoutMs = 10)
    private val healthStore = InMemoryServerHealthStore()
    private val runner = HealthCheckRunner(tester, healthStore, { 5L })

    private val listPrefs = io.ucc.applogic.InMemoryServerListPrefs()

    private fun TestScope.vm(): Pair<ServersViewModel, Job> {
        val vm = ServersViewModel(repo, refresher, selection, manager, runner, healthStore, now = { 5L }, listPrefs = listPrefs)
        val job = vm.state.onEach { }.launchIn(this) // keep WhileSubscribed state hot
        return vm to job
    }

    private suspend fun seed(text: String, source: ProfileSource = ProfileSource.Manual) = importer.import(text, source).profiles.also { store.upsertAll(it) }

    @Test fun `search and favorites filter, favorites sort first`() = runTest(dispatcher) {
        val (a, b, c) = seed("trojan://pw@berlin.example.com:443#Berlin\nvless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls#Paris\ntrojan://pw@1.2.3.6:443#Oslo")
        repo.setFavorite(c.id, true)
        val (vm, job) = vm()
        advanceUntilIdle()
        assertEquals(listOf("Oslo", "Berlin", "Paris"), vm.state.value.groups.single().rows.map { it.profile.name })
        vm.onQueryChanged("vless"); advanceUntilIdle()
        assertEquals(listOf("Paris"), vm.state.value.groups.single().rows.map { it.profile.name })
        vm.onQueryChanged("berlin.example"); advanceUntilIdle()
        assertEquals(listOf("Berlin"), vm.state.value.groups.single().rows.map { it.profile.name })
        vm.onQueryChanged("zzz"); advanceUntilIdle()
        assertTrue(vm.state.value.nothingMatches)
        vm.onQueryChanged(""); vm.toggleFavoritesOnly(); advanceUntilIdle()
        assertEquals(listOf("Oslo"), vm.state.value.groups.single().rows.map { it.profile.name })
        job.cancel()
    }

    @Test fun `select updates shared selection and active flag follows manager state`() = runTest(dispatcher) {
        val (a, b) = seed("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B")
        val (vm, job) = vm()
        vm.select(b.id)
        manager.state.value = ConnectionState.Connected(a.id, 0L)
        advanceUntilIdle()
        val rows = vm.state.value.groups.single().rows.associateBy { it.profile.name }
        assertTrue(rows["B"]!!.selected); assertFalse(rows["B"]!!.active)
        assertTrue(rows["A"]!!.active); assertFalse(rows["A"]!!.selected)
        assertEquals(b.id, selection.selectedProfileId)
        job.cancel()
    }

    @Test fun `rename dialog flow`() = runTest(dispatcher) {
        val (a) = seed("trojan://pw@1.2.3.4:443#A")
        val (vm, job) = vm()
        vm.startRename(a.id); advanceUntilIdle()
        assertEquals(a.id, vm.state.value.renaming?.id)
        vm.confirmRename("Renamed"); advanceUntilIdle()
        assertNull(vm.state.value.renaming)
        assertEquals("Renamed", store.saved[a.id]!!.name)
        job.cancel()
    }

    @Test fun `delete requires confirmation, emits count, and reports the blocked active profile`() = runTest(dispatcher) {
        val (a, b) = seed("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B")
        manager.state.value = ConnectionState.Connected(a.id, 0L)
        val (vm, job) = vm()
        val effects = ArrayList<ServersEffect>()
        val ej = vm.effects.onEach { effects += it }.launchIn(this)
        vm.enterSelection(a.id); vm.toggleChecked(b.id); advanceUntilIdle()
        assertEquals(setOf(a.id, b.id), vm.state.value.checked)
        vm.requestDelete(vm.state.value.checked); advanceUntilIdle()
        assertIs<PendingDelete.Profiles>(vm.state.value.confirmDelete)
        assertEquals(2, store.current().size, "nothing deleted before confirmation")
        vm.confirmDelete(); advanceUntilIdle()
        assertNull(vm.state.value.confirmDelete); assertFalse(vm.state.value.selectionMode)
        assertEquals(listOf(a.id), store.current().map { it.id })
        assertTrue(effects.any { it is ServersEffect.DeleteBlockedActive })
        assertEquals(1, effects.filterIsInstance<ServersEffect.Deleted>().single().count)
        ej.cancel(); job.cancel()
    }

    @Test fun `share emits link text once as an effect and never stores it in state`() = runTest(dispatcher) {
        val (a) = seed("trojan://pw@1.2.3.4:443#A")
        val (vm, job) = vm()
        val effects = ArrayList<ServersEffect>()
        val ej = vm.effects.onEach { effects += it }.launchIn(this)
        advanceUntilIdle()
        vm.share(setOf(a.id)); advanceUntilIdle()
        val share = assertIs<ServersEffect.Share>(effects.single())
        assertTrue(share.text.startsWith("trojan://pw@1.2.3.4:443"))
        assertTrue(vm.effects.replayCache.isEmpty(), "export text must not be replayed to late collectors")
        assertFalse(vm.state.value.selectionMode)
        ej.cancel(); job.cancel()
    }

    @Test fun `subscription refresh shows progress and reports the merge result`() = runTest(dispatcher) {
        val sub = Subscription("s1", "https://example.com/s", "S", 1L)
        subs.upsert(sub)
        fetcher.body = "trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B"
        val (vm, job) = vm()
        val effects = ArrayList<ServersEffect>()
        val ej = vm.effects.onEach { effects += it }.launchIn(this)
        advanceUntilIdle()
        vm.refresh(sub); advanceUntilIdle()
        val r = assertIs<ServersEffect.Refreshed>(effects.single())
        assertEquals(2, r.added)
        val group = vm.state.value.groups.single { it.subscription?.id == "s1" }
        assertFalse(group.refreshing); assertEquals(2, group.rows.size)
        ej.cancel(); job.cancel()
    }

    @Test fun `delete subscription confirmation carries the member count and removes the group`() = runTest(dispatcher) {
        val sub = Subscription("s1", "https://example.com/s", "S", 1L)
        subs.upsert(sub)
        val members = importer.import("trojan://pw@1.2.3.4:443#A", ProfileSource.Subscription("s1")).profiles.map { it.copy(metadata = it.metadata.copy(groupId = "s1")) }
        store.upsertAll(members)
        val (vm, job) = vm()
        advanceUntilIdle()
        vm.requestDeleteSubscription(sub); advanceUntilIdle()
        val pending = assertIs<PendingDelete.SubscriptionGroup>(vm.state.value.confirmDelete)
        assertEquals(1, pending.memberCount)
        vm.confirmDelete(); advanceUntilIdle()
        assertTrue(store.current().isEmpty()); assertNull(subs.saved["s1"])
        assertNotNull(vm.state.value); assertTrue(vm.state.value.isEmpty)
        job.cancel()
    }

    @Test fun `reachability results appear per row and test-all covers visible rows only`() = runTest(dispatcher) {
        val (berlin, paris, oslo) = seed("trojan://pw@berlin.example.com:443#Berlin\nvless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls#Paris\nhysteria2://pw@1.2.3.6:443#Oslo")
        val (vm, job) = vm()
        advanceUntilIdle()
        fun row(id: String) = vm.state.value.groups.flatMap { it.rows }.first { it.profile.id == id }
        assertNull(row(paris.id).lastTest)
        assertEquals(HealthStatus.UNKNOWN, row(paris.id).status)

        vm.testReachability(paris.id); advanceUntilIdle()
        assertEquals(ConnectionTestResult.ok(25), row(paris.id).lastTest)
        assertFalse(row(paris.id).testing)
        assertEquals(HealthStatus.HEALTHY, row(paris.id).status, "test result is persisted as health")
        assertEquals(25L, row(paris.id).health.latencyMs)

        vm.onQueryChanged("o"); advanceUntilIdle() // matches Oslo and Berlin (protocol "trojan"); Paris/vless does not match
        vm.testVisibleReachability(); advanceUntilIdle()
        assertEquals(TestFailure.UNSUPPORTED, row(oslo.id).lastTest?.failure)
        assertEquals(TestFailure.TIMEOUT, row(berlin.id).lastTest?.failure)
        assertEquals(HealthStatus.UNKNOWN, row(oslo.id).status, "UDP-only cannot be rated by a TCP test")
        assertEquals(HealthStatus.OFFLINE, row(berlin.id).status, "never worked and failed = offline")
        assertFalse(vm.state.value.testingAll)
        job.cancel()
    }

    // ---- v1.0.1: sort by latency / hide failed / delete failed ----

    @Test fun `sort by latency puts measured servers first, untested next, failed last`() = runTest(dispatcher) {
        // Alpha: reachable (25 ms). Beta: times out. Gamma: hysteria2 → TCP tester says UNSUPPORTED (local reason, not failed).
        val (alpha, beta, gamma) = seed("trojan://pw@1.2.3.4:443#Alpha\ntrojan://pw@beta.example.com:443#Beta\nhysteria2://pw@1.2.3.6:443#Gamma")
        val (vm, job) = vm()
        advanceUntilIdle()
        fun ids() = vm.state.value.groups.flatMap { it.rows }.map { it.profile.id }
        assertEquals(listOf(alpha.id, beta.id, gamma.id), ids(), "default order is by name")

        vm.testVisibleReachability(); advanceUntilIdle()
        vm.setSortByLatency(true); advanceUntilIdle()
        assertTrue(vm.state.value.sortByLatency)
        assertEquals(listOf(alpha.id, gamma.id, beta.id), ids(), "measured, then untested/unsupported, then failed")
        assertEquals(setOf(beta.id), vm.state.value.failedIds, "only server-attributable failures count")
        assertTrue(listPrefs.sortByLatency, "preference is persisted through the prefs store")
        job.cancel()
    }

    @Test fun `hide failed removes failed rows but never the active or selected one`() = runTest(dispatcher) {
        val (alpha, beta, delta) = seed("trojan://pw@1.2.3.4:443#Alpha\ntrojan://pw@beta.example.com:443#Beta\ntrojan://pw@delta.example.com:443#Delta")
        val (vm, job) = vm()
        advanceUntilIdle()
        vm.testVisibleReachability(); advanceUntilIdle()
        vm.select(delta.id); advanceUntilIdle()
        vm.setHideFailed(true); advanceUntilIdle()
        val visible = vm.state.value.groups.flatMap { it.rows }.map { it.profile.id }
        assertEquals(listOf(alpha.id, delta.id), visible, "Beta hidden; Delta failed but is selected so it stays")
        assertEquals(1, vm.state.value.hiddenCount)
        assertEquals(3, vm.state.value.totalCount, "total still counts hidden rows")
        assertEquals(setOf(beta.id), vm.state.value.failedIds)
        job.cancel()
    }

    @Test fun `delete failed asks for confirmation and removes exactly the failed servers`() = runTest(dispatcher) {
        val (alpha, beta, delta) = seed("trojan://pw@1.2.3.4:443#Alpha\ntrojan://pw@beta.example.com:443#Beta\ntrojan://pw@delta.example.com:443#Delta")
        val (vm, job) = vm()
        advanceUntilIdle()
        vm.requestDeleteFailed(); advanceUntilIdle()
        assertNull(vm.state.value.confirmDelete, "nothing failed yet → no dialog")

        vm.testVisibleReachability(); advanceUntilIdle()
        vm.requestDeleteFailed(); advanceUntilIdle()
        val pending = assertIs<PendingDelete.Failed>(vm.state.value.confirmDelete)
        assertEquals(setOf(beta.id, delta.id), pending.ids)
        vm.confirmDelete(); advanceUntilIdle()
        assertEquals(listOf(alpha.id), store.current().map { it.id })
        assertNull(vm.state.value.confirmDelete)
        job.cancel()
    }
}
