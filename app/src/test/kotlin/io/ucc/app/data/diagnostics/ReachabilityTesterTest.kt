package io.ucc.app.data.diagnostics

import io.ucc.app.data.diagnostics.ReachabilityTester.Result
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReachabilityTesterTest {
    private fun p(id: String, proto: Protocol = Protocol.VLESS, host: String = "h.$id") =
        ConnectionProfile(id = id, name = id, protocol = proto, address = host, port = 443)

    private fun tester(dialer: ReachabilityTester.Dialer) = ReachabilityTester(dialer, UnconfinedTestDispatcher(), timeoutMs = 10)

    @Test fun `classifies dial outcomes`() = runTest {
        val t = tester { host, _, _ ->
            when (host) {
                "h.ok" -> 42L
                "h.slow" -> throw SocketTimeoutException()
                "h.refused" -> throw ConnectException("Connection refused")
                "h.nodns" -> throw UnknownHostException(host)
                else -> throw IllegalStateException("boom")
            }
        }
        assertEquals(Result.Ok(42), t.test(p("ok")))
        assertEquals(Result.Timeout, t.test(p("slow")))
        assertEquals(Result.Refused, t.test(p("refused")))
        assertEquals(Result.Unresolved, t.test(p("nodns")))
        assertEquals(Result.Failed("IllegalStateException"), t.test(p("other")))
    }

    @Test fun `udp-only protocols are not tested and the dialer is never called`() = runTest {
        var calls = 0
        val t = tester { _, _, _ -> calls++; 1L }
        for (proto in listOf(Protocol.HYSTERIA, Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD)) {
            assertEquals(Result.NotApplicable, t.test(p("x", proto)))
        }
        assertEquals(0, calls)
    }

    @Test fun `testAll reports every profile`() = runTest {
        val t = tester { host, _, _ -> host.length.toLong() }
        val seen = HashMap<String, Result>()
        t.testAll(listOf(p("a"), p("b"), p("c", Protocol.TUIC))) { id, r -> seen[id] = r }
        assertEquals(3, seen.size)
        assertEquals(Result.NotApplicable, seen["c"])
        assertEquals(Result.Ok(3), seen["a"])
    }
}
