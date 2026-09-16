package io.ucc.app.data

import io.ucc.core.engine.ConnectionState
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRepositoryTest {
    private val store = FakeProfileStore()
    private val subs = FakeSubscriptionStore()
    private val manager = FakeConnectionManager()
    private val repo = ServerRepository(store, subs, manager, now = { 42L })
    private val importer = testImporter()

    private suspend fun seed(text: String, source: ProfileSource = ProfileSource.Manual) =
        importer.import(text, source).profiles.also { store.upsertAll(it) }

    @Test fun `rename marks userRenamed and bumps updatedAt, blank or same name is a no-op`() = runTest {
        val (p) = seed("trojan://pw@1.2.3.4:443#A")
        assertFalse(repo.rename(p.id, "   "))
        assertFalse(repo.rename(p.id, "A"))
        assertTrue(repo.rename(p.id, " My A "))
        val after = store.saved[p.id]!!
        assertEquals("My A", after.name)
        assertTrue(after.metadata.userRenamed)
        assertEquals(42L, after.metadata.updatedAtEpochMs)
        assertEquals(p.fingerprint, after.fingerprint) // renaming never changes identity
    }

    @Test fun `favorite toggles and is idempotent`() = runTest {
        val (p) = seed("trojan://pw@1.2.3.4:443#A")
        assertTrue(repo.setFavorite(p.id, true))
        assertFalse(repo.setFavorite(p.id, true))
        assertTrue(store.saved[p.id]!!.metadata.favorite)
        assertTrue(repo.setFavorite(p.id, false))
        assertFalse(store.saved[p.id]!!.metadata.favorite)
    }

    @Test fun `delete removes profiles but refuses the active one`() = runTest {
        val (a, b) = seed("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B")
        manager.state.value = ConnectionState.Connected(a.id, 0L)
        val r = repo.delete(setOf(a.id, b.id))
        assertEquals(1, r.deleted); assertTrue(r.blockedActive)
        assertEquals(listOf(a.id), store.current().map { it.id })
        manager.state.value = ConnectionState.Disconnected
        assertEquals(1, repo.delete(setOf(a.id)).deleted)
        assertTrue(store.current().isEmpty())
    }

    @Test fun `a profile whose last connection failed can be deleted`() = runTest {
        val (a) = seed("trojan://pw@1.2.3.4:443#A")
        manager.state.value = ConnectionState.Error(a.id, io.ucc.core.engine.ConnectionError.ConnectionTimeout("probe"))
        val r = repo.delete(setOf(a.id))
        assertEquals(1, r.deleted); assertFalse(r.blockedActive)
    }

    @Test fun `deleteSubscription removes members and the record, favorites included`() = runTest {
        val sub = io.ucc.core.config.subscription.Subscription("s1", "https://x/sub", "S", 1L)
        subs.upsert(sub)
        val members = importer.import("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B", ProfileSource.Subscription("s1")).profiles
            .mapIndexed { i, p -> p.copy(metadata = p.metadata.copy(groupId = "s1", favorite = i == 0)) }
        store.upsertAll(members)
        seed("trojan://pw@9.9.9.9:443#manual")
        val r = repo.deleteSubscription("s1")
        assertEquals(2, r.deleted)
        assertNull(subs.saved["s1"])
        assertEquals(listOf("manual"), store.current().map { it.name })
    }

    @Test fun `deleteSubscription is refused entirely while one of its members is connected`() = runTest {
        subs.upsert(io.ucc.core.config.subscription.Subscription("s1", "https://x/sub", "S", 1L))
        val members = importer.import("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B", ProfileSource.Subscription("s1")).profiles
            .map { it.copy(metadata = it.metadata.copy(groupId = "s1")) }
        store.upsertAll(members)
        manager.state.value = ConnectionState.Connecting(members[0].id)
        val r = repo.deleteSubscription("s1")
        assertTrue(r.blockedActive)
        assertEquals(1, r.deleted)
        assertTrue(subs.saved.containsKey("s1"), "record kept so the group stays consistent with the surviving member")
    }

    @Test fun `exportLinks returns re-importable links with credentials in store order`() = runTest {
        val ps = seed("trojan://pw@1.2.3.4:443#A\nvless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls#B")
        val e = repo.exportLinks(setOf(ps[1].id, ps[0].id))
        assertEquals(2, e.exported); assertEquals(0, e.skipped)
        val lines = e.text.lines()
        assertTrue(lines[0].startsWith("trojan://pw@")); assertTrue(lines[1].startsWith("vless://"))
        val again = testImporter().import(e.text, ProfileSource.Manual).profiles
        assertEquals(ps.map { it.fingerprint }, again.map { it.fingerprint })
    }

    @Test fun `groups puts manual first then one group per subscription, orphans fall back to manual`() = runTest {
        subs.upsert(io.ucc.core.config.subscription.Subscription("s1", "https://x/sub", "S", 1L))
        seed("trojan://pw@9.9.9.9:443#manual")
        val member = importer.import("trojan://pw@1.2.3.4:443#A", ProfileSource.Subscription("s1")).profiles.single()
        store.upsertAll(listOf(member.copy(metadata = member.metadata.copy(groupId = "s1"))))
        val orphan = importer.import("trojan://pw@1.2.3.5:443#orphan", ProfileSource.Subscription("gone")).profiles.single()
        store.upsertAll(listOf(orphan.copy(metadata = orphan.metadata.copy(groupId = "gone"))))
        val groups = repo.groups.first()
        assertEquals(2, groups.size)
        assertNull(groups[0].subscription); assertEquals(listOf("manual", "orphan"), groups[0].profiles.map { it.name })
        assertEquals("s1", groups[1].subscription?.id); assertEquals(listOf("A"), groups[1].profiles.map { it.name })
    }
}
