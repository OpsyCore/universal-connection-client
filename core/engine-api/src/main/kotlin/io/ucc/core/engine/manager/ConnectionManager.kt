package io.ucc.core.engine.manager

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for tunnel state. UI issues commands; it never
 * mutates state directly.
 */
public interface ConnectionManager {
    public val state: StateFlow<ConnectionState>

    /**
     * Every transition in order, without conflation (bounded replay). Use this
     * for the Logs screen and for tests; use [state] for rendering.
     */
    public val transitions: Flow<ConnectionState>

    /** Latest real statistics from the core; null while not running. */
    public val statistics: StateFlow<CoreStatistics?>

    /** Human-readable, redacted timeline of what the manager did (bounded). */
    public val events: Flow<ConnectionEvent>

    /**
     * Connects to [profileId]. If already connected to another profile, performs a switch.
     * [options] = null means "use the current user settings" (see [StartOptionsProvider]);
     * every caller — UI, service restart, boot — should pass null unless it has a specific reason.
     */
    public fun connect(profileId: String, options: CoreStartOptions? = null)

    public fun disconnect()

    /** Called by the platform layer when the process is being restored and the service is already running. */
    public fun attachRunningTunnel(profileId: String, sinceEpochMs: Long)
}

public data class ConnectionEvent(
    val epochMs: Long,
    val message: String,
    val error: ConnectionError? = null,
)
