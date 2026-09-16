package io.ucc.app.data.diagnostics

import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Measures how long a plain TCP handshake to `address:port` takes.
 *
 * This is deliberately **not** a proxy speed test and is labelled as
 * "reachability" in the UI: it tells the user whether the server port answers
 * from the current network and roughly how far away it is. It does not use the
 * proxy protocol, does not send credentials and does not prove the proxy works.
 * UDP-only protocols (Hysteria, Hysteria2, TUIC, WireGuard) cannot be tested
 * this way and are reported as [Result.NotApplicable].
 */
class ReachabilityTester(
    private val dialer: Dialer = SocketDialer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Int = 4_000,
    private val parallelism: Int = 6,
) {
    sealed class Result {
        data class Ok(val rttMs: Long) : Result()
        data object Timeout : Result()
        data object Refused : Result()
        data object Unresolved : Result()
        data object NotApplicable : Result()
        data class Failed(val reason: String) : Result()
    }

    fun interface Dialer {
        /** Returns the connect time in ms or throws. */
        fun connect(host: String, port: Int, timeoutMs: Int): Long
    }

    object SocketDialer : Dialer {
        override fun connect(host: String, port: Int, timeoutMs: Int): Long {
            val start = System.nanoTime()
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            return (System.nanoTime() - start) / 1_000_000
        }
    }

    suspend fun test(profile: ConnectionProfile): Result {
        if (profile.protocol in udpOnly) return Result.NotApplicable
        return withContext(io) {
            try {
                Result.Ok(dialer.connect(profile.address, profile.port, timeoutMs))
            } catch (e: SocketTimeoutException) {
                Result.Timeout
            } catch (e: UnknownHostException) {
                Result.Unresolved
            } catch (e: java.net.ConnectException) {
                if (e.message.orEmpty().contains("refused", ignoreCase = true)) Result.Refused else Result.Failed(e.javaClass.simpleName)
            } catch (e: Exception) {
                Result.Failed(e.javaClass.simpleName)
            }
        }
    }

    /** Tests many profiles with bounded parallelism; [onResult] is called as each finishes. */
    suspend fun testAll(profiles: List<ConnectionProfile>, onResult: suspend (String, Result) -> Unit) = coroutineScope {
        val gate = Semaphore(parallelism)
        profiles.map { p -> async { gate.withPermit { onResult(p.id, test(p)) } } }.awaitAll()
        Unit
    }

    private companion object {
        val udpOnly = setOf(Protocol.HYSTERIA, Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD)
    }
}
