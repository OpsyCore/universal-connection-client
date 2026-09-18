package io.ucc.core.smart

import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.ucc.core.platform.platformIoDispatcher

/**
 * Engine-neutral connection test. Implementations must be safe to run while a
 * tunnel is active, must never log addresses or credentials, and must honour
 * coroutine cancellation.
 */
public interface ConnectionTester {
    public suspend fun test(profile: ConnectionProfile): ConnectionTestResult
}

/**
 * What this client can measure **without starting a second core**: DNS
 * resolution + TCP handshake to the server endpoint, timed.
 *
 * It answers "is the port reachable from this network and how far away is
 * it?". It does not speak the proxy protocol, so it cannot detect a wrong
 * password or a blocked protocol — the tunnel itself reports those, and the
 * app records tunnel outcomes into the same health store. UDP-only protocols
 * (Hysteria, Hysteria2, TUIC, WireGuard) cannot be probed with TCP and are
 * reported as [TestFailure.UNSUPPORTED], which does not count against them.
 *
 * Failure classification is done by the platform [Dialer], which maps its
 * socket errors onto [DialFailure]; TLS and auth failures cannot occur at this
 * layer and are never produced here.
 */
public class TcpConnectionTester(
    private val dialer: Dialer = defaultDialer(),
    private val io: CoroutineDispatcher = platformIoDispatcher(),
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : ConnectionTester {

    /**
     * Platform socket boundary. Returns the connect time in ms, or throws
     * [DialException] with the classified failure; any other exception is
     * treated as [TestFailure.UNKNOWN]. JVM/Android: [SocketDialer] (jvmMain).
     */
    public fun interface Dialer {
        public fun connect(host: String, port: Int, timeoutMs: Int): Long
    }

    /** Thrown by a [Dialer] to report a classified connection failure. */
    public class DialException(public val failure: TestFailure, message: String? = null) : Exception(message ?: failure.name)

    override suspend fun test(profile: ConnectionProfile): ConnectionTestResult {
        if (profile.protocol in UDP_ONLY) return ConnectionTestResult.failed(TestFailure.UNSUPPORTED)
        return withContext(io) {
            try {
                ConnectionTestResult.ok(dialer.connect(profile.address, profile.port, timeoutMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DialException) {
                ConnectionTestResult.failed(e.failure)
            } catch (e: Exception) {
                ConnectionTestResult.failed(TestFailure.UNKNOWN)
            }
        }
    }

    public companion object {
        public const val DEFAULT_TIMEOUT_MS: Int = 4_000
        /** The platform's default [Dialer]. */
        public fun defaultDialer(): Dialer = platformDialer()
        public val UDP_ONLY: Set<Protocol> = setOf(Protocol.HYSTERIA, Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD)
    }
}

/**
 * Runs tests with bounded parallelism, records every outcome into the
 * [ServerHealthStore] and guarantees that one profile is never tested twice at
 * the same time (a second request joins the in-flight test).
 *
 * Parallelism default 4: enough to test a typical subscription (20–60 servers)
 * in a few seconds at a 4 s timeout, small enough not to saturate a mobile
 * radio or trip carrier connection-rate limits.
 */
public class HealthCheckRunner(
    private val tester: ConnectionTester,
    private val store: ServerHealthStore,
    private val clock: () -> Long,
    private val networkTransport: () -> String? = { null },
    parallelism: Int = DEFAULT_PARALLELISM,
) {
    private val gate = Semaphore(parallelism)
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<ConnectionTestResult>>()

    /** Tests one profile and records the result. A concurrent request for the same profile joins the in-flight test. */
    public suspend fun test(profile: ConnectionProfile): ConnectionTestResult = coroutineScope {
        val existing = inFlightLock.withLock { inFlight[profile.id] }
        if (existing != null) return@coroutineScope existing.await()
        val job = async {
            gate.withPermit {
                val r = try {
                    tester.test(profile)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        store.update(profile.fingerprint) { it.record(ConnectionTestResult.failed(TestFailure.CANCELLED), clock(), networkTransport()) }
                    }
                    throw e
                }
                store.update(profile.fingerprint) { it.record(r, clock(), networkTransport()) }
                r
            }
        }
        inFlightLock.withLock { inFlight[profile.id] = job }
        try {
            job.await()
        } finally {
            inFlightLock.withLock { if (inFlight[profile.id] === job) inFlight.remove(profile.id) }
        }
    }

    /** Tests all [profiles]; [onEach] is invoked as results arrive. Cancellation stops pending tests. */
    public suspend fun testAll(profiles: List<ConnectionProfile>, onEach: suspend (ConnectionProfile, ConnectionTestResult) -> Unit = { _, _ -> }) {
        coroutineScope {
            profiles.map { p -> async { onEach(p, test(p)) } }.awaitAll()
        }
    }

    public companion object {
        public const val DEFAULT_PARALLELISM: Int = 4
    }
}
