package io.ucc.core.smart

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * JVM/Android dialer: DNS resolution + TCP handshake via `java.net.Socket`,
 * classifying the JDK exception types exactly as the pre-KMP tester did.
 */
public object SocketDialer : TcpConnectionTester.Dialer {
    override fun connect(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.nanoTime()
        try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
        } catch (e: SocketTimeoutException) {
            throw TcpConnectionTester.DialException(TestFailure.TIMEOUT)
        } catch (e: UnknownHostException) {
            throw TcpConnectionTester.DialException(TestFailure.DNS_FAILURE)
        } catch (e: NoRouteToHostException) {
            throw TcpConnectionTester.DialException(TestFailure.NETWORK_UNAVAILABLE)
        } catch (e: ConnectException) {
            throw TcpConnectionTester.DialException(classifyConnect(e.message.orEmpty()))
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    /** Exposed for tests: the message-based classification of `ConnectException`. */
    public fun classifyConnect(message: String): TestFailure = when {
        message.contains("refused", ignoreCase = true) -> TestFailure.CONNECTION_REFUSED
        message.contains("unreachable", ignoreCase = true) -> TestFailure.NETWORK_UNAVAILABLE
        message.contains("timed out", ignoreCase = true) -> TestFailure.TIMEOUT
        else -> TestFailure.UNKNOWN
    }
}

internal actual fun platformDialer(): TcpConnectionTester.Dialer = SocketDialer
