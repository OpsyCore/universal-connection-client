package io.ucc.core.smart

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The JDK-exception classification the tester relied on before the dialer became a platform boundary. */
class SocketDialerTest {
    @Test fun classifiesConnectExceptionMessages() {
        assertEquals(TestFailure.CONNECTION_REFUSED, SocketDialer.classifyConnect("Connection refused"))
        assertEquals(TestFailure.NETWORK_UNAVAILABLE, SocketDialer.classifyConnect("Network is unreachable"))
        assertEquals(TestFailure.TIMEOUT, SocketDialer.classifyConnect("connect timed out"))
        assertEquals(TestFailure.UNKNOWN, SocketDialer.classifyConnect("something else"))
    }

    @Test fun refusedPortIsReportedAsDialException() {
        val port = ServerSocket(0).use { it.localPort }   // closed again → nothing listens there
        val e = assertFailsWith<TcpConnectionTester.DialException> { SocketDialer.connect("127.0.0.1", port, 2_000) }
        assertEquals(TestFailure.CONNECTION_REFUSED, e.failure)
    }

    @Test fun openPortMeasuresLatency() {
        ServerSocket(0).use { server ->
            val ms = SocketDialer.connect("127.0.0.1", server.localPort, 2_000)
            assertTrue(ms in 0..2_000, "latency $ms")
        }
    }

    @Test fun isTheDefaultDialer() {
        assertEquals(SocketDialer, TcpConnectionTester.defaultDialer())
    }
}
