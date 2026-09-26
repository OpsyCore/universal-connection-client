package io.ucc.core.smart

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreDelayProbe
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.DelayProbeSession
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.CancellationException

/**
 * A [ConnectionTester] that can test many profiles inside one shared session
 * (one core boot instead of one per profile). [HealthCheckRunner.testAll] uses
 * [batch] when the tester implements this; single tests go through [test].
 */
public interface BatchConnectionTester : ConnectionTester {
    public suspend fun <T> batch(profiles: List<ConnectionProfile>, block: suspend () -> T): T
}

/**
 * **Real delay test**: an HTTP request (default `generate_204`) through each
 * server via [CoreDelayProbe] — the same code path user traffic takes, so a
 * wrong password, a blocked protocol or a dead upstream all show up as failures,
 * and the number is an end-to-end round-trip, not a TCP handshake.
 *
 * Profiles the probe cannot host fall back to [fallback] (default: the TCP
 * tester), so nothing is silently skipped. Errors are classified from the
 * core's [ConnectionError]; the probe never reveals addresses or credentials.
 */
public class HttpDelayConnectionTester(
    private val probe: CoreDelayProbe,
    private val startOptions: () -> CoreStartOptions,
    private val url: () -> String = { DEFAULT_URL },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val fallback: ConnectionTester? = TcpConnectionTester(),
) : BatchConnectionTester {

    private class Open(val ids: Set<String>, val session: DelayProbeSession)

    // One batch at a time is enforced by the probe itself (sessions are serialised); this only routes calls.
    private var open: Open? = null

    override suspend fun <T> batch(profiles: List<ConnectionProfile>, block: suspend () -> T): T {
        val hosted = profiles.filter(probe::supports).distinctBy { it.id }
        if (hosted.isEmpty()) return block()
        return probe.open(hosted, startOptions()) { session ->
            open = Open(hosted.map { it.id }.toSet(), session)
            try { block() } finally { open = null }
        }
    }

    override suspend fun test(profile: ConnectionProfile): ConnectionTestResult {
        if (!probe.supports(profile)) return fallback?.test(profile) ?: ConnectionTestResult.failed(TestFailure.UNSUPPORTED)
        val current = open
        if (current != null && profile.id in current.ids) return measure(current.session, profile)
        return try {
            probe.open(listOf(profile), startOptions()) { s -> measure(s, profile) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreException) {
            ConnectionTestResult.failed(classify(e.error))
        } catch (e: Exception) {
            ConnectionTestResult.failed(TestFailure.UNKNOWN)
        }
    }

    private suspend fun measure(session: DelayProbeSession, profile: ConnectionProfile): ConnectionTestResult = try {
        ConnectionTestResult.ok(session.measure(profile.id, url(), timeoutMs))
    } catch (e: CancellationException) {
        throw e
    } catch (e: CoreException) {
        ConnectionTestResult.failed(classify(e.error))
    } catch (e: Exception) {
        ConnectionTestResult.failed(TestFailure.UNKNOWN)
    }

    public companion object {
        public const val DEFAULT_URL: String = "https://www.gstatic.com/generate_204"
        public const val DEFAULT_TIMEOUT_MS: Long = 5_000

        public fun classify(error: ConnectionError): TestFailure = when (error) {
            is ConnectionError.ConnectionTimeout -> TestFailure.TIMEOUT
            is ConnectionError.DnsFailure -> TestFailure.DNS_FAILURE
            is ConnectionError.TlsFailure -> TestFailure.TLS_FAILURE
            is ConnectionError.AuthenticationFailure -> TestFailure.AUTH_FAILURE
            is ConnectionError.NetworkUnavailable -> TestFailure.NETWORK_UNAVAILABLE
            is ConnectionError.UnsupportedProtocol -> TestFailure.UNSUPPORTED
            is ConnectionError.InvalidConfiguration -> TestFailure.UNSUPPORTED
            else -> TestFailure.UNKNOWN
        }

        /** Accepts `http(s)://host[:port][/path]`; used by Settings validation. */
        public fun isValidTestUrl(url: String): Boolean {
            val u = url.trim()
            if (!(u.startsWith("https://") || u.startsWith("http://"))) return false
            val rest = u.substringAfter("://")
            val host = rest.substringBefore('/').substringBefore(':')
            return host.isNotEmpty() && ' ' !in u && Regex("^[A-Za-z0-9.-]+$").matches(host)
        }
    }
}
