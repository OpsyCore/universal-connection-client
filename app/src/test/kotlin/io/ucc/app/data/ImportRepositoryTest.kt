package io.ucc.app.data

import io.ucc.core.config.ImportItem
import io.ucc.core.config.InputFormat
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.config.subscription.SubscriptionInfo
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportRepositoryTest {
    private val links = "trojan://pw@1.2.3.4:443#A\nvless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls#B"

    @Test fun `plan then commit persists only selected savable profiles`() = runTest {
        val store = FakeProfileStore()
        val repo = newRepository(profiles = store)
        val plan = repo.planFromText(links, ProfileSource.Manual)
        assertEquals(2, plan.newCount)
        val onlyFirst = setOf(plan.items[0].profile.id)
        val r = repo.commit(plan, onlyFirst)
        assertEquals(1, r.savedIds.size)
        assertEquals(listOf("A"), store.current().map { it.name })
        assertEquals(ProfileSource.Manual, store.current()[0].metadata.source)
    }

    @Test fun `committing a duplicate id is ignored and counted as skipped`() = runTest {
        val store = FakeProfileStore()
        val repo = newRepository(profiles = store)
        repo.commit(repo.planFromText("trojan://pw@1.2.3.4:443#A", ProfileSource.Manual), setOf("id-1"))
        val plan2 = repo.planFromText("trojan://pw@1.2.3.4:443#A renamed", ProfileSource.Clipboard)
        assertIs<ImportItem.Status.Duplicate>(plan2.items.single().status)
        val r = repo.commit(plan2, setOf(plan2.items.single().profile.id))
        assertEquals(0, r.savedIds.size); assertEquals(1, r.skipped)
        assertEquals(1, store.current().size)
    }

    @Test fun `source is stamped from the entry point`() = runTest {
        val repo = newRepository()
        val qr = repo.planFromText("trojan://pw@1.2.3.4:443#Q", ProfileSource.QrCode)
        assertEquals(ProfileSource.QrCode, qr.items.single().profile.metadata.source)
        val file = repo.planFromText("trojan://pw@1.2.3.5:443#F", ProfileSource.File("x.txt"))
        assertEquals(ProfileSource.File("x.txt"), file.items.single().profile.metadata.source)
    }

    @Test fun `subscription plan groups profiles and persists the subscription on commit only`() = runTest {
        val store = FakeProfileStore(); val subs = FakeSubscriptionStore()
        val fetcher = FakeFetcher().apply {
            body = Base64.getEncoder().encodeToString(links.toByteArray())
            info = SubscriptionInfo(uploadBytes = 1, downloadBytes = 2, totalBytes = 100, expireEpochSeconds = 999_999_999)
            name = "My Provider"
        }
        val repo = newRepository(profiles = store, subs = subs, fetcher = fetcher)
        val sp = repo.planFromSubscription("https://example.com/sub")
        assertEquals("https://example.com/sub", fetcher.lastUrl)
        assertEquals(InputFormat.BASE64_SHARE_LINKS, sp.plan.format)
        assertEquals(2, sp.plan.newCount)
        assertTrue(subs.saved.isEmpty(), "nothing persisted before confirm")
        assertTrue(sp.plan.items.all { it.profile.metadata.source == ProfileSource.Subscription(sp.subscriptionId) })

        val r = repo.commit(sp.plan, sp.plan.items.map { it.profile.id }.toSet(), sp)
        assertEquals(2, r.savedIds.size)
        val sub = assertNotNull(subs.saved[sp.subscriptionId])
        assertEquals("My Provider", sub.name)
        assertEquals(5_000L, sub.lastFetchedAtEpochMs)
        assertEquals(100, sub.lastInfo?.totalBytes)
        assertTrue(store.current().all { it.metadata.groupId == sp.subscriptionId })
    }

    @Test fun `re-adding the same subscription keeps its original addedAt`() = runTest {
        val subs = FakeSubscriptionStore()
        val fetcher = FakeFetcher().apply { body = "trojan://pw@1.2.3.4:443#A" }
        val repo = newRepository(subs = subs, fetcher = fetcher)
        val first = repo.planFromSubscription("https://example.com/sub")
        repo.commit(first.plan, first.plan.items.map { it.profile.id }.toSet(), first)
        val addedAt = subs.saved.values.single().addedAtEpochMs
        val again = repo.planFromSubscription("https://EXAMPLE.com/sub/")
        assertEquals(first.subscriptionId, again.subscriptionId)
        repo.commit(again.plan, emptySet(), again)
        assertEquals(addedAt, subs.saved.values.single().addedAtEpochMs)
    }

    @Test fun `subscription fetch errors propagate typed`() = runTest {
        val fetcher = FakeFetcher().apply { error = SubscriptionFetchError.Http(403) }
        val repo = newRepository(fetcher = fetcher)
        val e = assertFailsWith<SubscriptionFetchError.Http> { repo.planFromSubscription("https://x") }
        assertEquals(403, e.code)
    }

    @Test fun `store failure surfaces and leaves nothing half-saved`() = runTest {
        val store = FakeProfileStore().apply { failNextWrite = true }
        val repo = newRepository(profiles = store)
        val plan = repo.planFromText(links, ProfileSource.Manual)
        assertFailsWith<java.io.IOException> { repo.commit(plan, plan.items.map { it.profile.id }.toSet()) }
        assertTrue(store.current().isEmpty())
    }

    @Test fun `unsupported profile can still be saved when explicitly selected`() = runTest {
        val store = FakeProfileStore()
        val repo = newRepository(profiles = store)
        val plan = repo.planFromText("hy2://pw@h.example.com:443#H", ProfileSource.Manual)
        val item = plan.items.single()
        assertIs<ImportItem.Status.Unsupported>(item.status)
        assertTrue(plan.items.none { it.selectedByDefault })
        repo.commit(plan, setOf(item.profile.id))
        assertEquals(1, store.current().size)
    }
}
