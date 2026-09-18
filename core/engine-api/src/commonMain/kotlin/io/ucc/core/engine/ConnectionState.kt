package io.ucc.core.engine

/**
 * The single source of truth for what the tunnel is doing.
 *
 * ```
 * Disconnected ─► Starting ─► Connecting ─► Connected ─┐
 *      ▲              │           │            │        │ network change / core hiccup
 *      │              ▼           ▼            ▼        ▼
 *      │            Error ◄──── Error ◄───── Stopping  Reconnecting ─► Connected
 *      └──────────────┴───────────────────────┘             │
 *                                                            └► Error / Disconnected
 * ```
 */
public sealed class ConnectionState {
    public data object Disconnected : ConnectionState()

    /** VPN permission granted, service starting, core not yet launched. */
    public data class Starting(val profileId: String) : ConnectionState()

    /** Core launched and TUN established; waiting for the first successful upstream check. */
    public data class Connecting(val profileId: String) : ConnectionState()

    public data class Connected(
        val profileId: String,
        val sinceEpochMs: Long,
    ) : ConnectionState()

    /** Tunnel is still up but upstream is being re-established (e.g. Wi-Fi → cellular). */
    public data class Reconnecting(
        val profileId: String,
        val attempt: Int,
        val reason: String,
    ) : ConnectionState()

    public data class Stopping(val profileId: String?) : ConnectionState()

    public data class Error(
        val profileId: String?,
        val error: ConnectionError,
    ) : ConnectionState()

    public val profileIdOrNull: String?
        get() = when (this) {
            Disconnected -> null
            is Starting -> profileId
            is Connecting -> profileId
            is Connected -> profileId
            is Reconnecting -> profileId
            is Stopping -> profileId
            is Error -> profileId
        }

    /** True while the tunnel is expected to carry traffic (Connecting, Connected, Reconnecting). */
    public val isActive: Boolean
        get() = this is Connecting || this is Connected || this is Reconnecting

    /** True while a transition is in flight and user commands should be debounced. */
    public val isTransitioning: Boolean
        get() = this is Starting || this is Connecting || this is Stopping || this is Reconnecting
}
