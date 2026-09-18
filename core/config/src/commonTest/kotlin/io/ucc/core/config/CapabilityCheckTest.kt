package io.ucc.core.config

import io.ucc.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapabilityCheckTest {
    @Test fun `transport names are canonical`() {
        assertEquals("tcp", CapabilityCheck.transportName(Transport.Tcp))
        assertEquals("ws", CapabilityCheck.transportName(Transport.WebSocket()))
        assertEquals("grpc", CapabilityCheck.transportName(Transport.Grpc()))
        assertEquals("http", CapabilityCheck.transportName(Transport.HttpUpgradeOrH2(upgrade = false)))
        assertEquals("httpupgrade", CapabilityCheck.transportName(Transport.HttpUpgradeOrH2(upgrade = true)))
        assertEquals("kcp", CapabilityCheck.transportName(Transport.Unsupported("kcp")))
        assertNull(CapabilityCheck.transportName(Transport.None))
    }
}
