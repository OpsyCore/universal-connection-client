package io.ucc.applogic

import kotlin.test.Test
import kotlin.test.assertEquals

/** Vectors produced with `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", UTC)` — the format LogBuffer used before KMP. */
class IsoTimeTest {
    @Test fun `known vectors`() {
        assertEquals("1970-01-01T00:00:00.000Z", isoUtc(0))
        assertEquals("1969-12-31T23:59:59.999Z", isoUtc(-1))
        assertEquals("2000-02-29T12:34:56.789Z", isoUtc(951_827_696_789L))
        assertEquals("2024-02-29T00:00:00.000Z", isoUtc(1_709_164_800_000L))
        assertEquals("2026-09-19T10:00:00.000Z", isoUtc(1_789_812_000_000L))
        assertEquals("2038-01-19T03:14:07.000Z", isoUtc(2_147_483_647_000L))
        assertEquals("2100-03-01T00:00:00.000Z", isoUtc(4_107_542_400_000L))
    }
}
