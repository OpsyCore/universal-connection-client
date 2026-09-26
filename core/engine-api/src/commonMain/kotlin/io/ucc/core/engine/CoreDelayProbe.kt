package io.ucc.core.engine

import io.ucc.core.model.ConnectionProfile

/**
 * Optional core capability: a **real** HTTP delay test through a server without
 * making it the active tunnel (engines opt in by implementing this on their
 * [CoreAdapter]). One session boots a lightweight, TUN-less core instance that
 * contains an outbound per profile; [DelayProbeSession.measure] then performs
 * an HTTP request (e.g. `generate_204`) through that outbound and returns the
 * round-trip time in milliseconds.
 *
 * Contract:
 *  - Sessions never touch the active tunnel and never show a system dialog.
 *  - While a tunnel is up, probe sockets are protected so the measurement does
 *    not go through the tunnel; without a tunnel plain sockets are used.
 *  - [DelayProbeSession.measure] throws [CoreException] with a classified
 *    [ConnectionError] on failure ([ConnectionError.ConnectionTimeout] when the
 *    request exceeded `timeoutMs`).
 *  - Profiles for which [supports] is false must not be passed to [open].
 */
public interface CoreDelayProbe {
    public fun supports(profile: ConnectionProfile): Boolean

    /** Opens a session for [profiles] built with [options] (so e.g. TLS fragmentation matches the real tunnel), runs [block], then tears the instance down. */
    public suspend fun <T> open(profiles: List<ConnectionProfile>, options: CoreStartOptions, block: suspend (DelayProbeSession) -> T): T
}

public interface DelayProbeSession {
    /** Real HTTP round-trip through the outbound of [profileId] to [url]; ms on success, [CoreException] otherwise. */
    public suspend fun measure(profileId: String, url: String, timeoutMs: Long): Long
}
