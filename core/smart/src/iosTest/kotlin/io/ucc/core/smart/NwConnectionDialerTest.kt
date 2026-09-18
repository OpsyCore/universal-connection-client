package io.ucc.core.smart

import platform.posix.ECONNREFUSED
import platform.posix.EHOSTUNREACH
import platform.posix.ENETUNREACH
import platform.posix.EPERM
import platform.posix.ETIMEDOUT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Compiles on Linux CI; executes only on a macOS host (documented in docs/KMP_IOS.md). */
class NwConnectionDialerTest {
    @Test
    fun posixClassificationMatchesJvmSemantics() {
        assertEquals(TestFailure.CONNECTION_REFUSED, NwConnectionDialer.classifyPosix(ECONNREFUSED))
        assertEquals(TestFailure.TIMEOUT, NwConnectionDialer.classifyPosix(ETIMEDOUT))
        assertEquals(TestFailure.NETWORK_UNAVAILABLE, NwConnectionDialer.classifyPosix(ENETUNREACH))
        assertEquals(TestFailure.NETWORK_UNAVAILABLE, NwConnectionDialer.classifyPosix(EHOSTUNREACH))
        assertEquals(TestFailure.UNKNOWN, NwConnectionDialer.classifyPosix(EPERM))
    }

    @Test
    fun defaultDialerIsNetworkFramework() {
        assertSame(NwConnectionDialer, TcpConnectionTester.defaultDialer())
    }

    @Test
    fun loopbackClosedPortIsRefusedOrTimesOut() {
        // Port 9 (discard) is closed on every stock Apple device/simulator; no external network needed.
        val e = assertFailsWith<TcpConnectionTester.DialException> { NwConnectionDialer.connect("127.0.0.1", 9, 2_000) }
        assertEquals(true, e.failure == TestFailure.CONNECTION_REFUSED || e.failure == TestFailure.TIMEOUT, e.failure.name)
    }
}
