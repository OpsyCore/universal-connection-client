package io.ucc.app.data

import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.config.subscription.SubscriptionInfo
import io.ucc.core.engine.ConnectionState
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionRefresherTest {
    private val store = FakeProfileStore()
    private val subs = FakeSubscriptionStore()
    private val fetcher = FakeFetcher()
    private val manager = FakeConnectionManager()
    private var clock = 10_000L
    private val refresher = SubscriptionRefresher(
        importer = testImporter(), fetcher = fetcher, profiles = store, subscriptions = subs, manager = manager,
        now = { clock }, parseDispatcher = Dispatchers.Unconfined,
    )
    private val a = "trojan://pw@1.2.3.4:443#A"
    private val b = "trojan://pw@1.2.3.5:443#B"
    private val c = "trojan://pw@1.2.3.6:443#C"

    private suspend fun addSubscription(id: String = "s1", body: String = "$a\n$b", lastFetched: Long? = 1L): Subscription {
        val s = Subscription(id, "https://example.com/$id", "Sub $id", 1L, lastFetchedAtEpochMs = lastFetched)
        subs.upsert(s)
        fetcher.body = body
        return s
    }

    @Test fun `refresh merges upstream changes and updates the record`() = runTest {
        addSubscription()
        refresher.refresh("s1")
        assertEquals(setOf("A", "B"), store.current().map { it.name }.toSet())
        // user renames B and favorites A; upstream drops A and adds C
        val bId = store.current().first { it.name == "B" }.id
        val aId = store.current().first { it.name == "A" }.id
        store.upsertAll(listOf(store.saved[bId]!!.let { it.copy(name = "Mine", metadata = it.metadata.copy(userRenamed = true)) }))
        store.upsertAll(listOf(store.saved[aId]!!.let { it.copy(metadata = it.metadata.copy(favorite = true)) }))
        fetcher.body = "trojan://pw@1.2.3.5:443#B-renamed-upstream\n$c"
        fetcher.info = SubscriptionInfo(1, 2, 100, null)
        clock = 20_000L
        val o = assertIs<SubscriptionRefresher.Outcome.Updated>(refresher.refresh("s1"))
        assertEquals(1, o.result.added); assertEquals(0, o.result.removed); assertEquals(1, o.result.keptFavorites)
        val names = store.current().associateBy({ it.id }, { it.name })
        assertEquals("Mine", names[bId]); assertEquals("A", names[aId]); assertTrue(names.values.contains("C"))
        val rec = subs.saved["s1"]!!
        assertEquals(20_000L, rec.lastFetchedAtEpochMs); assertEquals(100L, rec.lastInfo?.totalBytes); assertNull(rec.lastError)
    }

    @Test fun `fetch failure leaves profiles untouched and records a redacted error`() = runTest {
        addSubscription(); refresher.refresh("s1")
        val before = store.current()
        fetcher.error = SubscriptionFetchError.Http(503)
        val o = assertIs<SubscriptionRefresher.Outcome.Failed>(refresher.refresh("s1"))
        assertEquals(503, o.error.code)
        assertEquals(before, store.current())
        val err = assertNotNull(subs.saved["s1"]!!.lastError)
        assertEquals("http:503", err)
        assertTrue(!err.contains("example.com"))
    }

    @Test fun `empty or unparseable body never wipes the group`() = runTest {
        addSubscription(); refresher.refresh("s1")
        fetcher.body = "hello world"
        val o = assertIs<SubscriptionRefresher.Outcome.EmptyBody>(refresher.refresh("s1"))
        assertEquals(1, o.unreadable)
        assertEquals(2, store.current().size)
    }

    @Test fun `connected profile is never deleted by a refresh`() = runTest {
        addSubscription(); refresher.refresh("s1")
        val aId = store.current().first { it.name == "A" }.id
        manager.state.value = ConnectionState.Connected(aId, 0L)
        fetcher.body = c
        val o = assertIs<SubscriptionRefresher.Outcome.Updated>(refresher.refresh("s1"))
        assertEquals(1, o.result.removed) // B
        assertTrue(store.saved.containsKey(aId))
    }

    @Test fun `manual profile with the same fingerprint is not duplicated into the group`() = runTest {
        store.upsertAll(testImporter(generateSequence(100) { it + 1 }.map { "m-$it" }.iterator()).import(a, ProfileSource.Manual).profiles)
        addSubscription(body = "$a\n$b")
        val o = assertIs<SubscriptionRefresher.Outcome.Updated>(refresher.refresh("s1"))
        assertEquals(1, o.result.skippedDuplicates); assertEquals(1, o.result.added)
        assertEquals(2, store.current().size)
    }

    @Test fun `unknown subscription id is NotFound and writes nothing`() = runTest {
        assertIs<SubscriptionRefresher.Outcome.NotFound>(refresher.refresh("nope"))
        assertEquals(0, store.writes)
    }

    @Test fun `refreshAllDue honours autoUpdate, minimum interval and server interval`() = runTest {
        clock = 100L * 3_600_000L
        addSubscription("due", lastFetched = clock - 13 * 3_600_000L)
        addSubscription("fresh", lastFetched = clock - 1 * 3_600_000L)
        addSubscription("off", lastFetched = 0L).let { subs.upsert(it.copy(autoUpdate = false)) }
        addSubscription("server-24h", lastFetched = clock - 13 * 3_600_000L).let { subs.upsert(it.copy(updateIntervalHours = 24)) }
        addSubscription("never", lastFetched = null)
        val outcomes = refresher.refreshAllDue(minIntervalMs = 12 * 3_600_000L)
        val refreshed = outcomes.filterIsInstance<SubscriptionRefresher.Outcome.Updated>().map { it.subscription.id }.toSet()
        assertEquals(setOf("due", "never"), refreshed)
    }
}
