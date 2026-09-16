package io.ucc.app.ui.servers

import io.ucc.app.data.FakeConnectionManager
import io.ucc.app.data.FakeFetcher
import io.ucc.app.data.FakeProfileStore
import io.ucc.app.data.FakeSelection
import io.ucc.app.data.FakeSubscriptionStore
import io.ucc.app.data.ServerRepository
import io.ucc.app.data.SubscriptionRefresher
import io.ucc.app.data.testImporter
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

    private fun TestScope.vm(): Pair<ServersViewModel, Job> {
        val vm = ServersViewModel(repo, refresher, selection, manager)
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
        vm.enterSelection(a.id); vm.toggleChecked(b.id)
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
        assertFalse(vm.state.value.toString().contains("pw@"))
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
}
