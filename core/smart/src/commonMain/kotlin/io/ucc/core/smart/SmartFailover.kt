package io.ucc.core.smart

import io.ucc.core.engine.ConnectionError

/**
 * Pure failover policy for Smart mode. It does not own state transitions —
 * the ConnectionManager remains the single state machine — it only decides,
 * given a terminal failure of the current tunnel, whether Smart should try
 * another server, and which one.
 *
 * Semantics (see docs/SMART_SELECTION.md):
 *  - Manual mode: never switch; the manager's own reconnect policy already
 *    retried the same server.
 *  - Smart mode: the manager first retries the same server (its normal
 *    reconnect/backoff). Only when the manager gives up with a terminal
 *    [ConnectionError] does Smart pick the next non-offline candidate in the
 *    ranking, at most [maxSwitchesPerSession] times per user-initiated
 *    connect, never returning to a server already tried in this session.
 *  - Non-server errors (VPN permission/revoked, invalid configuration of the
 *    tunnel itself, network unavailable) never trigger a switch: another
 *    server would not help.
 */
public class SmartFailoverPolicy(
    private val selector: SmartServerSelector,
    /** 3 switches = up to 4 distinct servers per connect; more would look like an endless loop to the user. */
    public val maxSwitchesPerSession: Int = 3,
) {
    /** Per-session bookkeeping; create a new one on every user-initiated Smart connect. */
    public class Session(public val startedWith: String) {
        public val tried: MutableSet<String> = linkedSetOf(startedWith)
        public var switches: Int = 0
            internal set
    }

    public sealed class Decision {
        public data class Switch(val to: Candidate) : Decision()
        public data class GiveUp(val reason: Reason) : Decision()
    }

    public enum class Reason { NOT_SMART_MODE, ERROR_NOT_SERVER_RELATED, SWITCH_LIMIT, NO_CANDIDATE }

    public fun onTerminalFailure(
        smartMode: Boolean,
        session: Session?,
        error: ConnectionError,
        ranked: List<Candidate>,
        nowMs: Long,
        currentTransport: String? = null,
    ): Decision {
        if (!smartMode || session == null) return Decision.GiveUp(Reason.NOT_SMART_MODE)
        if (!error.isServerRelated()) return Decision.GiveUp(Reason.ERROR_NOT_SERVER_RELATED)
        if (session.switches >= maxSwitchesPerSession) return Decision.GiveUp(Reason.SWITCH_LIMIT)
        val current = session.tried.last()
        val next = selector.nextAfter(ranked, current, nowMs, exclude = session.tried, currentTransport = currentTransport) ?: return Decision.GiveUp(Reason.NO_CANDIDATE)
        session.tried += next.profile.id
        session.switches++
        return Decision.Switch(next)
    }

    public companion object {
        /** Maps a tunnel failure to the health failure class recorded against the server. */
        public fun toTestFailure(error: ConnectionError): TestFailure = when (error) {
            is ConnectionError.ConnectionTimeout -> TestFailure.TIMEOUT
            is ConnectionError.DnsFailure -> TestFailure.DNS_FAILURE
            is ConnectionError.TlsFailure -> TestFailure.TLS_FAILURE
            is ConnectionError.AuthenticationFailure -> TestFailure.AUTH_FAILURE
            is ConnectionError.UnsupportedProtocol -> TestFailure.UNSUPPORTED
            is ConnectionError.NetworkUnavailable -> TestFailure.NETWORK_UNAVAILABLE
            is ConnectionError.CoreFailure -> TestFailure.UNKNOWN
            is ConnectionError.InvalidConfiguration -> TestFailure.UNSUPPORTED
            is ConnectionError.VpnPermissionDenied, is ConnectionError.VpnRevoked -> TestFailure.CANCELLED
            is ConnectionError.Unknown -> TestFailure.UNKNOWN
        }

        /** True when trying a *different* server could plausibly help. */
        public fun ConnectionError.isServerRelated(): Boolean = when (this) {
            is ConnectionError.ConnectionTimeout, is ConnectionError.DnsFailure, is ConnectionError.TlsFailure,
            is ConnectionError.AuthenticationFailure, is ConnectionError.CoreFailure, is ConnectionError.Unknown -> true
            is ConnectionError.UnsupportedProtocol, is ConnectionError.InvalidConfiguration,
            is ConnectionError.VpnPermissionDenied, is ConnectionError.VpnRevoked, is ConnectionError.NetworkUnavailable -> false
        }
    }
}
