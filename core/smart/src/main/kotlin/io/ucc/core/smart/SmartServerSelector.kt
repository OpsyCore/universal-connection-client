package io.ucc.core.smart

import io.ucc.core.model.ConnectionProfile

/** Human-readable health class shown in the UI. Derived, never stored. */
public enum class HealthStatus {
    /** Recent success, no open failure streak. */
    HEALTHY,
    /** Has succeeded before but the latest attempts failed (streak below the offline threshold). */
    DEGRADED,
    /** Failure streak reached [ServerHealthEvaluator.OFFLINE_AFTER_CONSECUTIVE_FAILURES], or never succeeded and failed. */
    OFFLINE,
    /** No usable observation yet, or the last one is older than [ServerHealthEvaluator.STALE_AFTER_MS]. */
    UNKNOWN,
}

/**
 * Turns a raw [ServerHealth] record into a status and into the ordering keys
 * the selector uses. All thresholds are named constants with a rationale.
 */
public object ServerHealthEvaluator {
    /**
     * After this many server-attributable failures in a row the server is
     * treated as offline. 3 = one transient blip plus one retry does not
     * mark a server offline, a third failure does.
     */
    public const val OFFLINE_AFTER_CONSECUTIVE_FAILURES: Int = 3

    /**
     * Observations older than this no longer justify calling a server
     * healthy — networks change, servers get blocked. 30 min is long enough to
     * survive an app restart and short enough that yesterday's data is not
     * treated as truth.
     */
    public const val STALE_AFTER_MS: Long = 30L * 60_000

    /**
     * Two latencies within this band are treated as equal so that stability
     * (availability, failure history) breaks the tie rather than a 5 ms
     * difference that is below measurement noise on mobile networks.
     */
    public const val LATENCY_BAND_MS: Long = 50

    /**
     * @param currentTransport transport of the current default network
     *   ("wifi", "cellular", …) when known. Evidence gathered on a different
     *   transport is treated as UNKNOWN (re-measure) but is **not** erased:
     *   latency and counters stay in the record.
     */
    public fun status(h: ServerHealth, nowMs: Long, currentTransport: String? = null): HealthStatus {
        val last = h.lastCheckedAtEpochMs ?: return HealthStatus.UNKNOWN
        if (nowMs - last > STALE_AFTER_MS) return HealthStatus.UNKNOWN
        if (currentTransport != null && h.lastNetworkTransport != null && h.lastNetworkTransport != currentTransport) return HealthStatus.UNKNOWN
        return when {
            h.consecutiveFailures >= OFFLINE_AFTER_CONSECUTIVE_FAILURES -> HealthStatus.OFFLINE
            h.consecutiveFailures > 0 && h.lastSuccessAtEpochMs != null -> HealthStatus.DEGRADED
            h.consecutiveFailures > 0 -> HealthStatus.OFFLINE
            h.lastSuccessAtEpochMs != null -> HealthStatus.HEALTHY
            else -> HealthStatus.UNKNOWN // only non-attributable failures recorded (e.g. no network)
        }
    }

    public fun isStale(h: ServerHealth, nowMs: Long): Boolean =
        h.lastCheckedAtEpochMs?.let { nowMs - it > STALE_AFTER_MS } ?: true
}

/** One selector input: profile + its health + whether the active core can run it. */
public data class Candidate(
    val profile: ConnectionProfile,
    val health: ServerHealth,
    val supported: Boolean,
)

/** Result of a Smart selection. */
public sealed class Selection {
    /** [candidate] is the choice; [ranked] is the full ordering (best first) for display and failover. */
    public data class Chosen(val candidate: Candidate, val status: HealthStatus, val ranked: List<Candidate>) : Selection()
    /** Nothing supported is stored. */
    public data object NoCandidates : Selection()
    /** Every supported candidate has recent evidence of being down. Manual choice required. */
    public data class AllUnhealthy(val ranked: List<Candidate>) : Selection()
    /** No candidate has fresh data; the caller should test [toTest] (bounded) and retry. */
    public data class NeedsMeasurement(val toTest: List<Candidate>) : Selection()
}

/**
 * Deterministic, data-driven selection. Policy, in order of precedence:
 *
 *  1. Unsupported profiles (capability check) are never chosen.
 *  2. Status class: HEALTHY > DEGRADED > UNKNOWN > OFFLINE. A server that is
 *     known to work beats one we know nothing about, which beats one that
 *     is known to be down.
 *  3. Within HEALTHY/DEGRADED: rolling latency compared in [LATENCY_BAND_MS]
 *     bands (lower is better).
 *  4. Within a band: fewer consecutive failures, then higher availability
 *     (null availability sorts below any measured value), then more recent
 *     success (fresher evidence wins).
 *  5. Final deterministic tie-break: profile id, so repeated calls with the
 *     same data return the same server.
 *
 * Favourites are **not** a ranking input: a favourite is the user's manual
 * pick; Smart is the alternative to a manual pick. (Documented in
 * docs/SMART_SELECTION.md.)
 *
 * When no supported candidate has fresh data, the selector does not guess: it
 * returns [Selection.NeedsMeasurement] with a bounded list ([maxToTest]) so
 * the caller can test and call again.
 */
public class SmartServerSelector(
    private val maxToTest: Int = DEFAULT_MAX_TO_TEST,
) {
    public fun select(candidates: List<Candidate>, nowMs: Long, currentTransport: String? = null): Selection {
        val supported = candidates.filter { it.supported }
        if (supported.isEmpty()) return Selection.NoCandidates

        val withStatus = supported.map { it to ServerHealthEvaluator.status(it.health, nowMs, currentTransport) }
        val fresh = withStatus.filter { (_, s) -> s != HealthStatus.UNKNOWN }
        if (fresh.isEmpty()) {
            // Nothing fresh: prefer to (re)measure the ones with the best historical record first, then the rest by id.
            val order = supported.sortedWith(
                compareByDescending<Candidate> { it.health.lastSuccessAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.profile.id },
            )
            return Selection.NeedsMeasurement(order.take(maxToTest))
        }

        val ranked = withStatus.sortedWith(comparator(nowMs)).map { it.first }
        val best = withStatus.minWithOrNull(comparator(nowMs))!!
        return if (best.second == HealthStatus.OFFLINE) Selection.AllUnhealthy(ranked)
        else Selection.Chosen(best.first, best.second, ranked)
    }

    /** Next candidate after [currentId] in the ranking that is not OFFLINE; null when none. Used by failover. */
    public fun nextAfter(ranked: List<Candidate>, currentId: String, nowMs: Long, exclude: Set<String>, currentTransport: String? = null): Candidate? =
        ranked.firstOrNull { c ->
            c.profile.id != currentId && c.profile.id !in exclude &&
                ServerHealthEvaluator.status(c.health, nowMs, currentTransport) != HealthStatus.OFFLINE
        }

    private fun comparator(nowMs: Long): Comparator<Pair<Candidate, HealthStatus>> =
        compareBy<Pair<Candidate, HealthStatus>> { (_, s) -> statusRank(s) }
            .thenBy { (c, s) -> if (s == HealthStatus.HEALTHY || s == HealthStatus.DEGRADED) latencyBand(c.health) else Long.MAX_VALUE }
            .thenBy { (c, _) -> c.health.consecutiveFailures }
            .thenByDescending { (c, _) -> c.health.availability ?: -1.0 }
            .thenByDescending { (c, _) -> c.health.lastSuccessAtEpochMs ?: Long.MIN_VALUE }
            .thenBy { (c, _) -> c.profile.id }

    private fun statusRank(s: HealthStatus): Int = when (s) {
        HealthStatus.HEALTHY -> 0
        HealthStatus.DEGRADED -> 1
        HealthStatus.UNKNOWN -> 2
        HealthStatus.OFFLINE -> 3
    }

    private fun latencyBand(h: ServerHealth): Long =
        (h.rollingLatencyMs ?: h.latencyMs ?: Long.MAX_VALUE / 2) / ServerHealthEvaluator.LATENCY_BAND_MS

    public companion object {
        /** Bound on how many servers a cold Smart start may test; with parallelism 4 and 4 s timeout that is ≤ ~12 s. */
        public const val DEFAULT_MAX_TO_TEST: Int = 12
    }
}
