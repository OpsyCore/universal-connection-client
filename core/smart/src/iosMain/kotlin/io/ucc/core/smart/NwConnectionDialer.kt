@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.core.smart

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_domain_posix
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create
import platform.Network.nw_protocol_stack_set_transport_protocol
import platform.Network.nw_tcp_create_options
import platform.Network.nw_tcp_options_set_connection_timeout
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import platform.posix.EHOSTDOWN
import platform.posix.EHOSTUNREACH
import platform.posix.ENETDOWN
import platform.posix.ENETUNREACH
import platform.posix.ETIMEDOUT
import kotlin.time.TimeSource

/**
 * Apple dialer: DNS resolution + TCP handshake through Network.framework
 * (`nw_connection`), blocking the calling IO thread just like the JVM
 * `Socket.connect` does, so [TcpConnectionTester] behaves identically.
 *
 * Only the transport layer is measured (TLS disabled on the parameters), so the
 * result is comparable with the JVM [SocketDialer]. Failure mapping:
 *  - DNS error domain → [TestFailure.DNS_FAILURE]
 *  - POSIX `ECONNREFUSED` / `ECONNRESET` → [TestFailure.CONNECTION_REFUSED]
 *  - POSIX `ETIMEDOUT` or our own deadline → [TestFailure.TIMEOUT]
 *  - POSIX `ENETUNREACH` / `EHOSTUNREACH` / `ENETDOWN` / `EHOSTDOWN` → [TestFailure.NETWORK_UNAVAILABLE]
 *  - anything else → [TestFailure.UNKNOWN]
 * The UDP-only "unsupported" short-circuit stays in [TcpConnectionTester] and is untouched.
 */
public object NwConnectionDialer : TcpConnectionTester.Dialer {
    // attr = null is DISPATCH_QUEUE_SERIAL (the macro is a null pointer in the C headers).
    private val queue = dispatch_queue_create("io.ucc.core.smart.dialer", null)

    override fun connect(host: String, port: Int, timeoutMs: Int): Long {
        val params = nw_parameters_create()
        val stack = nw_parameters_copy_default_protocol_stack(params)
        val tcp = nw_tcp_create_options()
        nw_tcp_options_set_connection_timeout(tcp, (timeoutMs / 1000).coerceAtLeast(1).toUInt())
        nw_protocol_stack_set_transport_protocol(stack, tcp)   // plain TCP, no TLS

        val endpoint = nw_endpoint_create_host(host, port.toString())
        val connection = nw_connection_create(endpoint, params)
        val done = dispatch_semaphore_create(0)
        var outcome: TestFailure? = TestFailure.TIMEOUT      // null == connected
        var settled = false

        nw_connection_set_state_changed_handler(connection) { state, error ->
            if (settled) return@nw_connection_set_state_changed_handler
            when (state) {
                nw_connection_state_ready -> { outcome = null; settled = true; dispatch_semaphore_signal(done) }
                nw_connection_state_failed -> { outcome = classify(error); settled = true; dispatch_semaphore_signal(done) }
                // `waiting` = no viable path right now (no network, DNS failure). A probe must not hang on it.
                nw_connection_state_waiting -> { outcome = classify(error); settled = true; dispatch_semaphore_signal(done) }
                nw_connection_state_cancelled -> if (!settled) { settled = true; dispatch_semaphore_signal(done) }
                else -> Unit
            }
        }
        nw_connection_set_queue(connection, queue)
        val mark = TimeSource.Monotonic.markNow()
        nw_connection_start(connection)
        val deadline = dispatch_time(DISPATCH_TIME_NOW, timeoutMs.toLong() * 1_000_000L)
        val timedOut = dispatch_semaphore_wait(done, deadline) != 0L
        nw_connection_cancel(connection)
        if (timedOut) throw TcpConnectionTester.DialException(TestFailure.TIMEOUT)
        outcome?.let { throw TcpConnectionTester.DialException(it) }
        return mark.elapsedNow().inWholeMilliseconds
    }

    private fun classify(error: nw_error_t?): TestFailure {
        if (error == null) return TestFailure.NETWORK_UNAVAILABLE
        return when (nw_error_get_error_domain(error)) {
            nw_error_domain_dns -> TestFailure.DNS_FAILURE
            nw_error_domain_posix -> classifyPosix(nw_error_get_error_code(error))
            else -> TestFailure.UNKNOWN
        }
    }

    /** Exposed for tests: errno → failure class. */
    public fun classifyPosix(errno: Int): TestFailure = when (errno) {
        ECONNREFUSED, ECONNRESET -> TestFailure.CONNECTION_REFUSED
        ETIMEDOUT -> TestFailure.TIMEOUT
        ENETUNREACH, EHOSTUNREACH, ENETDOWN, EHOSTDOWN -> TestFailure.NETWORK_UNAVAILABLE
        else -> TestFailure.UNKNOWN
    }
}

internal actual fun platformDialer(): TcpConnectionTester.Dialer = NwConnectionDialer
