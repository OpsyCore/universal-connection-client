package io.ucc.core.config

import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionInfoTest {
    @Test fun `parses standard header`() {
        val i = assertNotNull(SubscriptionInfo.parse("upload=1000; download=2000; total=10000; expire=1700000000"))
        assertEquals(1000, i.uploadBytes); assertEquals(2000, i.downloadBytes); assertEquals(10000, i.totalBytes); assertEquals(1700000000, i.expireEpochSeconds)
        assertEquals(3000, i.usedBytes)
    }
    @Test fun `expired and exhausted detection`() {
        val i = SubscriptionInfo(uploadBytes = 6000, downloadBytes = 5000, totalBytes = 10000, expireEpochSeconds = 1_000)
        assertTrue(i.isExpired(nowEpochMs = 2_000_000))
        assertFalse(i.isExpired(nowEpochMs = 500_000))
        assertTrue(i.isQuotaExhausted())
        assertFalse(SubscriptionInfo(totalBytes = 0).isQuotaExhausted())
    }
    @Test fun `garbage header yields null`() { assertNull(SubscriptionInfo.parse("")); assertNull(SubscriptionInfo.parse("nonsense")) }
    @Test fun `subscription id is stable for normalized url`() {
        assertEquals(Subscription.idFor("https://EXAMPLE.com/sub/"), Subscription.idFor("https://example.com/sub"))
        assertTrue(Subscription.idFor("https://a") != Subscription.idFor("https://b"))
    }
}
