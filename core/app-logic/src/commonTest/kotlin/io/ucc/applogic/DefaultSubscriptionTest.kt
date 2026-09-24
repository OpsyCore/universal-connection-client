package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSubscriptionTest {
    @Test fun `id matches what a manual add of the same url would produce`() {
        assertEquals(Subscription.idFor(DefaultSubscription.URL), DefaultSubscription.ID)
        assertEquals(DefaultSubscription.ID, DefaultSubscription.record("x", 1).id)
        assertTrue(DefaultSubscription.URL.startsWith("https://"))
    }

    @Test fun `seeds exactly once and never over an existing record`() {
        assertTrue(DefaultSubscription.shouldSeed(alreadySeeded = false, existingIds = emptyList()))
        assertFalse(DefaultSubscription.shouldSeed(alreadySeeded = true, existingIds = emptyList()), "deleted by the user → stays deleted")
        assertFalse(DefaultSubscription.shouldSeed(alreadySeeded = false, existingIds = listOf(DefaultSubscription.ID)), "already present (manual add / restore)")
    }

    @Test fun `record is a normal auto-updating subscription with the given name`() {
        val r = DefaultSubscription.record("🚀 Public Free Servers", 42L)
        assertEquals("🚀 Public Free Servers", r.name); assertEquals(42L, r.addedAtEpochMs); assertTrue(r.autoUpdate)
        assertEquals(null, r.lastFetchedAtEpochMs)
    }
}
