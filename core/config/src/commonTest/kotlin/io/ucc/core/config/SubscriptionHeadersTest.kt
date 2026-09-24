package io.ucc.core.config

import io.ucc.core.config.subscription.SubscriptionHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionHeadersTest {
    @Test
    fun titlePlainAndBase64() {
        assertEquals("My Sub", SubscriptionHeaders.decodeTitle("  My Sub "))
        assertEquals("hello", SubscriptionHeaders.decodeTitle("base64:aGVsbG8="))
        assertNull(SubscriptionHeaders.decodeTitle("base64:***"))
        assertNull(SubscriptionHeaders.decodeTitle(""))
    }

    @Test
    fun dispositionPrefersStarForm() {
        assertEquals("سلام.txt", SubscriptionHeaders.fileNameFromDisposition("attachment; filename*=UTF-8''%D8%B3%D9%84%D8%A7%D9%85.txt; filename=\"x.txt\""))
        assertEquals("a b.yaml", SubscriptionHeaders.fileNameFromDisposition("attachment; filename=\"a+b.yaml\""))
        assertEquals("plain.txt", SubscriptionHeaders.fileNameFromDisposition("inline; filename=plain.txt"))
        assertNull(SubscriptionHeaders.fileNameFromDisposition("attachment"))
    }

    @Test
    fun resultCombinesHeaders() {
        val r = SubscriptionHeaders.result("body", "upload=1; download=2; total=10; expire=0", null, "attachment; filename=n.txt", " 12 ")
        assertEquals("body", r.body)
        assertEquals("n.txt", r.suggestedName)
        assertEquals(12, r.updateIntervalHours)
        assertEquals(10L, r.info?.totalBytes)
    }
}
