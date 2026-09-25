package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultSubscriptionTest {
    @Test fun `withdrawn ids match what a manual add of the same url produces`() {
        assertEquals(DefaultSubscription.WITHDRAWN_URLS.map { Subscription.idFor(it) }, DefaultSubscription.WITHDRAWN_IDS)
        assertEquals(DefaultSubscription.WITHDRAWN_IDS.size, DefaultSubscription.WITHDRAWN_IDS.toSet().size, "distinct")
        assertTrue(DefaultSubscription.WITHDRAWN_URLS.all { it.startsWith("https://") })
    }

    @Test fun `only withdrawn lists that are present are removed - user subscriptions never`() {
        val ids = DefaultSubscription.WITHDRAWN_IDS
        assertEquals(listOf(ids[0], ids[2]), DefaultSubscription.toRemove(listOf(ids[2], "mine", ids[0])))
        assertEquals(emptyList(), DefaultSubscription.toRemove(listOf("mine", "other")))
        assertEquals(emptyList(), DefaultSubscription.toRemove(emptyList()))
    }
}
