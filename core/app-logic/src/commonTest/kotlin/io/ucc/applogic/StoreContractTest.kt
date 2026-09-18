package io.ucc.applogic

import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract of the shared persistence boundaries and how the use-cases react to
 * store failures / cancellation — independent of the Android AES-GCM files
 * behind them (those keep their own tests in app/src/test).
 */
class StoreContractTest {
    private val two = testImporter().import("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B", ProfileSource.Manual).profiles

    @Test fun `profile store apply is atomic and observable`() = runTest {
        val store = FakeProfileStore()
        store.apply(two, emptyList())
        assertEquals(listOf("id-1", "id-2"), store.profiles.value.map { it.id })
        store.apply(emptyList(), listOf("id-1"))
        assertEquals(listOf("id-2"), store.current().map { it.id })
        assertEquals(2, store.writes)
    }

    @Test fun `subscription store round trip and delete`() = runTest {
        val subs = FakeSubscriptionStore()
        val s = io.ucc.core.config.subscription.Subscription(id = "s1", url = "https://x/y", name = "n", addedAtEpochMs = 1)
        subs.upsert(s); assertEquals(s, subs.byId("s1")); assertEquals(1, subs.all.value.size)
        subs.delete("s1"); assertNull(subs.byId("s1")); assertTrue(subs.all.value.isEmpty())
    }

    @Test fun `store write failure propagates from commit and leaves nothing half-saved`() = runTest {
        val profiles = FakeProfileStore().apply { failNextWrite = true }
        val repo = newRepository(profiles = profiles)
        val plan = repo.planFromText("trojan://pw@1.2.3.4:443#A", ProfileSource.Manual)
        assertFailsWith<StorageFailure> { repo.commit(plan, plan.items.map { it.profile.id }.toSet()) }
        assertTrue(profiles.current().isEmpty())
        // next write succeeds — the failure was not sticky
        repo.commit(plan, plan.items.map { it.profile.id }.toSet())
        assertEquals(1, profiles.current().size)
    }

    @Test fun `fetch errors from the platform fetcher surface unchanged from planFromSubscription`() = runTest {
        val fetcher = FakeFetcher().apply { error = io.ucc.core.config.subscription.SubscriptionFetchError.Http(503) }
        val e = assertFailsWith<io.ucc.core.config.subscription.SubscriptionFetchError.Http> { newRepository(fetcher = fetcher).planFromSubscription("https://x/sub") }
        assertEquals(503, e.code)
    }

    @Test fun `cancellation while a fetch is in flight cancels cleanly and persists nothing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fetcher = object : io.ucc.core.config.subscription.SubscriptionFetcher {
            override suspend fun fetch(url: String): io.ucc.core.config.subscription.SubscriptionFetchResult { gate.await(); error("unreachable") }
        }
        val profiles = FakeProfileStore(); val subs = FakeSubscriptionStore()
        val repo = ImportRepository(testImporter(), io.ucc.core.config.ImportPlanner(io.ucc.core.config.CapabilityCheck(testCapabilities)), profiles, subs, fetcher, now = { 1 }, parseDispatcher = Dispatchers.Unconfined)
        val job = async { repo.planFromSubscription("https://x/sub") }
        job.cancel()
        assertFailsWith<CancellationException> { withTimeout(1_000) { job.await() } }
        assertTrue(profiles.current().isEmpty()); assertTrue(subs.all.value.isEmpty())
    }
}
