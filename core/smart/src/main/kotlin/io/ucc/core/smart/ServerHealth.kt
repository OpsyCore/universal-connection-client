package io.ucc.core.smart

import kotlinx.serialization.Serializable

/**
 * Why a connection test failed. Deliberately coarse and secret-free: it is
 * persisted and shown to the user. Maps 1:1 onto user-facing strings.
 */
@Serializable
public enum class TestFailure {
    TIMEOUT,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    TLS_FAILURE,
    AUTH_FAILURE,
    UNSUPPORTED,
    NETWORK_UNAVAILABLE,
    CANCELLED,
    UNKNOWN,
    ;

    /**
     * Failures that say something about the *server* rather than about the
     * local network. Local-network failures are recorded but do not count
     * towards [ServerHealth.consecutiveFailures] (see [ServerHealth.record]).
     */
    public val attributableToServer: Boolean
        get() = this != NETWORK_UNAVAILABLE && this != CANCELLED && this != UNSUPPORTED
}

/** Structured outcome of one connection test. Never contains addresses or credentials. */
@Serializable
public data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long? = null,
    val failure: TestFailure? = null,
) {
    init {
        require(success == (failure == null)) { "success must be true iff failure is null" }
        require(success || latencyMs == null) { "a failed test cannot carry a latency" }
    }

    public companion object {
        public fun ok(latencyMs: Long): ConnectionTestResult = ConnectionTestResult(true, latencyMs, null)
        /** A tunnel that came up proves the server works even though no latency sample exists. */
        public val TUNNEL_UP: ConnectionTestResult = ConnectionTestResult(true, null, null)
        public fun failed(failure: TestFailure): ConnectionTestResult = ConnectionTestResult(false, null, failure)
    }
}

/**
 * Persistent, secret-free health record for one profile. Keyed by the profile's
 * content fingerprint (see [ServerHealthStore]), so a subscription refresh that
 * keeps the same endpoint keeps the same history, and a renamed profile keeps
 * it too.
 *
 * All timestamps are epoch millis. Everything here is derived from real test
 * or tunnel outcomes; nothing is estimated.
 */
@Serializable
public data class ServerHealth(
    /** Most recent successful latency in ms; null until the first success. */
    val latencyMs: Long? = null,
    /** Exponential moving average over successes (alpha [ServerHealth.EMA_ALPHA]); null until the first success. */
    val rollingLatencyMs: Long? = null,
    val latencySampleCount: Int = 0,
    val lastSuccessAtEpochMs: Long? = null,
    val lastFailureAtEpochMs: Long? = null,
    val lastFailure: TestFailure? = null,
    /** Server-attributable failures since the last success. */
    val consecutiveFailures: Int = 0,
    /** Lifetime counters (bounded by [MAX_COUNTED] to keep availability responsive). */
    val successCount: Int = 0,
    val failureCount: Int = 0,
    /** Transport of the default network at the last test ("wifi", "cellular", …) when known. */
    val lastNetworkTransport: String? = null,
) {
    /** Epoch of the most recent observation of any kind; null when nothing was ever recorded. */
    public val lastCheckedAtEpochMs: Long?
        get() = listOfNotNull(lastSuccessAtEpochMs, lastFailureAtEpochMs).maxOrNull()

    /**
     * Fraction of counted server-attributable attempts that succeeded, or null
     * when fewer than [MIN_SAMPLES_FOR_AVAILABILITY] attempts exist ("Not enough data").
     */
    public val availability: Double?
        get() {
            val total = successCount + failureCount
            return if (total < MIN_SAMPLES_FOR_AVAILABILITY) null else successCount.toDouble() / total
        }

    /** Applies one test outcome. Pure; returns the new record. */
    public fun record(result: ConnectionTestResult, atEpochMs: Long, networkTransport: String?): ServerHealth {
        if (result.success) {
            val l = result.latencyMs
            val ema = if (l == null) rollingLatencyMs else rollingLatencyMs?.let { (it * (1 - EMA_ALPHA) + l * EMA_ALPHA).toLong() } ?: l
            return copy(
                latencyMs = l ?: latencyMs,
                rollingLatencyMs = ema,
                latencySampleCount = if (l == null) latencySampleCount else latencySampleCount + 1,
                lastSuccessAtEpochMs = atEpochMs,
                consecutiveFailures = 0,
                successCount = (successCount + 1).coerceAtMost(MAX_COUNTED),
                failureCount = if (successCount + failureCount >= MAX_COUNTED) (failureCount - 1).coerceAtLeast(0) else failureCount,
                lastNetworkTransport = networkTransport,
            )
        }
        val f = result.failure!!
        if (!f.attributableToServer) {
            // Local problem: remember it for display, do not punish the server.
            return copy(lastFailureAtEpochMs = atEpochMs, lastFailure = f, lastNetworkTransport = networkTransport)
        }
        return copy(
            lastFailureAtEpochMs = atEpochMs,
            lastFailure = f,
            consecutiveFailures = consecutiveFailures + 1,
            failureCount = (failureCount + 1).coerceAtMost(MAX_COUNTED),
            successCount = if (successCount + failureCount >= MAX_COUNTED) (successCount - 1).coerceAtLeast(0) else successCount,
            lastNetworkTransport = networkTransport,
        )
    }

    public companion object {
        public val EMPTY: ServerHealth = ServerHealth()

        /** EMA weight of the newest sample: recent measurements dominate but one outlier does not flip the picture. */
        public const val EMA_ALPHA: Double = 0.3

        /** Cap on the success+failure window so availability reflects the recent past, not the profile's whole life. */
        public const val MAX_COUNTED: Int = 20

        /** Below this many attempts an availability percentage would be noise, so none is shown. */
        public const val MIN_SAMPLES_FOR_AVAILABILITY: Int = 3
    }
}
