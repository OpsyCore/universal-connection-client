package io.ucc.core.singbox.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibboxMetadataTest {
    @Test fun pin_is_explicitly_unresolved() {
        assertNull(LibboxMetadata.xcframeworkSha256); assertFalse(LibboxMetadata.isPinned); assertEquals("unpinned", LibboxMetadata.pinStatus)
    }
    @Test fun identity() {
        assertEquals("v1.13.21", LibboxMetadata.SING_BOX_VERSION)
        assertTrue(LibboxMetadata.SING_BOX_COMMIT.matches(Regex("^[0-9a-f]{40}$")))
        assertEquals(listOf("ios-arm64", "ios-arm64_x86_64-simulator"), LibboxMetadata.expectedSlices)
    }
}
