package io.ucc.core.config

import io.ucc.core.config.parser.LinkParser
import io.ucc.core.config.subscription.SubscriptionMerger
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionMergerTest {
    private var n = 0
    private val importer = ConfigImporter(LinkParser({ "id-${++n}" }, { 1L }))
    private val merger = SubscriptionMerger()
    private val sub = "sub-1"

    private fun parse(text: String, source: ProfileSource = ProfileSource.Subscription(sub)) = importer.import(text, source).profiles

    private fun inGroup(text: String): List<ConnectionProfile> =
        parse(text).map { it.copy(metadata = it.metadata.copy(groupId = sub, createdAtEpochMs = 1L, updatedAtEpochMs = 1L)) }

    private val a = "trojan://pw@1.2.3.4:443#A"
    private val b = "trojan://pw@1.2.3.5:443#B"
    private val c = "trojan://pw@1.2.3.6:443#C"

    @Test fun `first refresh inserts everything with the subscription as source and group`() {
        val r = merger.merge(sub, emptyList(), emptyList(), parse("$a\n$b"), nowMs = 10L)
        assertEquals(2, r.added); assertEquals(0, r.removed)
        assertEquals(2, r.toUpsert.size)
        assertTrue(r.toUpsert.all { it.metadata.groupId == sub && it.metadata.source == ProfileSource.Subscription(sub) })
        assertTrue(r.toUpsert.all { it.metadata.updatedAtEpochMs == 10L })
    }

    @Test fun `unchanged body produces no writes`() {
        val existing = inGroup("$a\n$b")
        val r = merger.merge(sub, existing, emptyList(), parse("$a\n$b"), nowMs = 20L)
        assertFalse(r.hasChanges)
        assertEquals(2, r.unchanged)
    }

    @Test fun `removed upstream entries are deleted, favorites survive`() {
        val existing = inGroup("$a\n$b").mapIndexed { i, p -> if (i == 1) p.copy(metadata = p.metadata.copy(favorite = true)) else p }
        val r = merger.merge(sub, existing, emptyList(), parse(c), nowMs = 20L)
        assertEquals(listOf(existing[0].id), r.toDeleteIds)
        assertEquals(1, r.removed); assertEquals(1, r.keptFavorites); assertEquals(1, r.added)
    }

    @Test fun `user rename is preserved but upstream rename is applied otherwise`() {
        val existing = inGroup("$a\n$b").mapIndexed { i, p ->
            if (i == 0) p.copy(name = "My A", metadata = p.metadata.copy(userRenamed = true)) else p
        }
        val r = merger.merge(sub, existing, emptyList(), parse("trojan://pw@1.2.3.4:443#A2\ntrojan://pw@1.2.3.5:443#B2"), nowMs = 20L)
        // both rows are rewritten (rawSource follows upstream), but only B takes the upstream name
        assertEquals(2, r.updated); assertEquals(0, r.unchanged); assertEquals(0, r.added); assertEquals(0, r.removed)
        val byId = r.toUpsert.associateBy { it.id }
        assertEquals("My A", byId.getValue(existing[0].id).name)
        assertTrue(byId.getValue(existing[0].id).metadata.userRenamed)
        val updatedB = byId.getValue(existing[1].id)
        assertEquals("B2", updatedB.name)
        assertEquals(1L, updatedB.metadata.createdAtEpochMs)
        assertEquals(20L, updatedB.metadata.updatedAtEpochMs)
    }

    @Test fun `matched profiles keep id, favorite and per-profile overrides`() {
        val existing = inGroup(a).map {
            it.copy(
                metadata = it.metadata.copy(favorite = true, lastUsedAtEpochMs = 99L),
                routing = it.routing.copy(bypassGlobalRules = true),
                dns = it.dns.copy(remoteDns = "https://1.1.1.1/dns-query"),
            )
        }
        val r = merger.merge(sub, existing, emptyList(), parse("trojan://pw@1.2.3.4:443#renamed"), nowMs = 20L)
        val m = r.toUpsert.single()
        assertEquals(existing[0].id, m.id)
        assertTrue(m.metadata.favorite); assertEquals(99L, m.metadata.lastUsedAtEpochMs)
        assertTrue(m.routing.bypassGlobalRules)
        assertEquals("https://1.1.1.1/dns-query", m.dns.remoteDns)
    }

    @Test fun `entries that already exist outside the group are skipped, not duplicated`() {
        val manual = parse(a, ProfileSource.Manual)
        val r = merger.merge(sub, emptyList(), manual, parse("$a\n$b"), nowMs = 20L)
        assertEquals(1, r.skippedDuplicates); assertEquals(1, r.added)
        assertEquals("B", r.toUpsert.single().name)
    }

    @Test fun `duplicate fingerprints inside the fetched body count once`() {
        val r = merger.merge(sub, emptyList(), emptyList(), parse(a) + parse("trojan://pw@1.2.3.4:443#A-copy"), nowMs = 20L)
        assertEquals(1, r.added); assertEquals(1, r.toUpsert.size)
    }

    @Test fun `empty upstream body removes non-favorites only`() {
        val existing = inGroup("$a\n$b").mapIndexed { i, p -> if (i == 0) p.copy(metadata = p.metadata.copy(favorite = true)) else p }
        val r = merger.merge(sub, existing, emptyList(), emptyList(), nowMs = 20L)
        assertEquals(listOf(existing[1].id), r.toDeleteIds)
        assertEquals(1, r.keptFavorites)
    }

    @Test fun `pinned ids (e g the connected profile) are never deleted`() {
        val existing = inGroup("$a\n$b")
        val r = merger.merge(sub, existing, emptyList(), emptyList(), nowMs = 20L, pinnedIds = setOf(existing[0].id))
        assertEquals(listOf(existing[1].id), r.toDeleteIds)
        assertEquals(1, r.keptFavorites)
    }
}
