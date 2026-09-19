package io.ucc.core.singbox.apple

import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.manager.ProfileProvider
import io.ucc.core.engine.manager.StartOptionsProvider
import io.ucc.core.engine.manager.TunnelHost
import io.ucc.iosvpn.TunnelEngine
import io.ucc.iosvpn.TunnelSession
import io.ucc.iosvpn.VpnError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The [TunnelEngine] the packet tunnel provider drives, backed by a [CoreAdapter]:
 *
 *   TunnelSession.start → AppleSingBoxTunnelEngine.start
 *       → ProfileProvider.byId (App Group profile store)
 *       → TunnelHost.acquire (utun bridge)
 *       → CoreAdapter.start(profile, StartOptionsProvider.current(), tun)   [singbox-config → Libbox]
 *
 * Own guarantees (JVM-tested): one core instance, duplicate start rejected while
 * STARTING/RUNNING, stop-during-start cancels and cleans up, start after stop allowed,
 * [terminate] (provider termination) releases the adapter deterministically. It holds no
 * connection state of its own beyond this small lifecycle — the shared state machine
 * stays `ConnectionManager` (host app mirror) and `TunnelSession` (provider).
 */
public class AppleSingBoxTunnelEngine(
    private val adapter: CoreAdapter,
    private val profiles: ProfileProvider,
    private val startOptions: StartOptionsProvider,
    private val tunnelHost: TunnelHost,
) : TunnelEngine {
    public enum class State { IDLE, STARTING, RUNNING, STOPPING, STOPPED, TERMINATED }

    private val _state = MutableStateFlow(State.IDLE)
    public val state: StateFlow<State> = _state
    private val mutex = Mutex()
    private var stopRequested = false

    override suspend fun start(request: TunnelSession.StartRequest) {
        mutex.withLock {
            when (_state.value) {
                State.STARTING, State.RUNNING -> throw VpnError.ProviderStartupFailure("engine already ${_state.value.name.lowercase()}")
                State.TERMINATED -> throw VpnError.ProviderStartupFailure("engine terminated")
                else -> Unit
            }
            _state.value = State.STARTING
            stopRequested = false
        }
        try {
            val profile = profiles.byId(request.profileId)
                ?: throw VpnError.InvalidTunnelConfiguration("profile not found")
            val options = startOptions.current().copy(mtu = request.tunnelConfiguration.mtu)
            val tun = tunnelHost.acquire(profile)
            if (stopRequested) throw VpnError.ProviderStartupFailure("cancelled before core start")
            try {
                adapter.start(profile, options, tun)
            } catch (e: CoreException) {
                throw VpnError.ProviderStartupFailure("${e.error::class.simpleName}: ${e.error.technicalDetail}")
            }
            mutex.withLock {
                if (stopRequested) throw VpnError.ProviderStartupFailure("cancelled during core start")
                _state.value = State.RUNNING
            }
        } catch (t: Throwable) {
            cleanupAfterFailedStart()
            throw when (t) {
                is VpnError -> t
                is CancellationException -> VpnError.ProviderStartupFailure("cancelled")
                else -> VpnError.ProviderStartupFailure(t::class.simpleName ?: "failure")
            }
        }
    }

    private suspend fun cleanupAfterFailedStart() {
        runCatching { adapter.stop() }
        runCatching { tunnelHost.release() }
        mutex.withLock { if (_state.value != State.TERMINATED) _state.value = State.STOPPED }
    }

    override suspend fun stop() {
        mutex.withLock {
            when (_state.value) {
                State.STARTING -> { stopRequested = true; return } // start() observes the flag and cleans up itself
                State.IDLE, State.STOPPED, State.TERMINATED -> return
                State.STOPPING -> return
                State.RUNNING -> _state.value = State.STOPPING
            }
        }
        val failure = runCatching { adapter.stop() }.exceptionOrNull()
        runCatching { tunnelHost.release() }
        mutex.withLock { if (_state.value == State.STOPPING) _state.value = State.STOPPED }
        if (failure != null) throw VpnError.ProviderShutdownFailure((failure as? CoreException)?.error?.let { it::class.simpleName } ?: failure::class.simpleName ?: "failure")
    }

    /** Provider process is going away: stop if needed and release the core for good. Idempotent. */
    public suspend fun terminate() {
        val stopFailure = runCatching { stop() }.exceptionOrNull()
        runCatching { (adapter as? AppleSingBoxCoreAdapter)?.shutdown() }
        mutex.withLock { _state.value = State.TERMINATED }
        stopFailure?.let { throw it }
    }

    override fun statistics(): Pair<Long, Long>? =
        (adapter as? AppleSingBoxCoreAdapter)?.takeIf { it.isRunning }?.lastStatistics?.let { it.uplinkTotalBytes to it.downlinkTotalBytes }
}
