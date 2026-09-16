package io.ucc.core.engine.manager

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreEvent
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.TunProvider
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.toLogString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Deterministic state machine driving [CoreAdapter] + [TunnelHost].
 *
 * Concurrency model: every command and every reactive trigger is serialized
 * through [commandMutex] on [scope]. Long operations (core start, probes,
 * backoff delays) run inside that lock, so a `disconnect()` issued during a
 * connect is applied right after the connect attempt settles. This keeps the
 * machine simple and prevents interleaved core start/stop calls.
 */
public class DefaultConnectionManager(
    private val scope: CoroutineScope,
    private val core: CoreAdapter,
    private val tunnelHost: TunnelHost,
    private val profiles: ProfileProvider,
    private val networkMonitor: NetworkMonitor,
    private val clock: Clock,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) : ConnectionManager {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _transitions = MutableSharedFlow<ConnectionState>(
        replay = 32,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val transitions: Flow<ConnectionState> = _transitions

    private val _statistics = MutableStateFlow<CoreStatistics?>(null)
    override val statistics: StateFlow<CoreStatistics?> = _statistics.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ConnectionEvent> = _events

    private val commandMutex = Mutex()

    private var activeProfile: ConnectionProfile? = null
    private var activeOptions: CoreStartOptions = CoreStartOptions()
    private var tun: TunProvider? = null
    private var currentNetworkKey: String? = null
    private var reactiveJobs: List<Job> = emptyList()

    init {
        reactiveJobs = listOf(
            scope.launch { core.statistics.collect { _statistics.value = it } },
            scope.launch {
                core.events.collect { event ->
                    if (event is CoreEvent.Fatal) onCoreFatal(event.error)
                }
            },
            scope.launch { networkMonitor.events.collect { onNetworkEvent(it) } },
            scope.launch { tunnelHost.revoked.collect { onRevoked() } },
        )
    }

    // ---------------------------------------------------------------- commands

    override fun connect(profileId: String, options: CoreStartOptions) {
        scope.launch {
            commandMutex.withLock { doConnect(profileId, options) }
        }
    }

    override fun disconnect() {
        scope.launch {
            commandMutex.withLock { doDisconnect(reason = "user") }
        }
    }

    override fun attachRunningTunnel(profileId: String, sinceEpochMs: Long) {
        scope.launch {
            commandMutex.withLock {
                if (_state.value !is ConnectionState.Disconnected) return@withLock
                val profile = profiles.byId(profileId) ?: return@withLock
                activeProfile = profile
                log("Re-attached to running tunnel for ${profile.toLogString()}")
                transition(ConnectionState.Connected(profileId, sinceEpochMs))
            }
        }
    }

    // ------------------------------------------------------------ transitions

    private suspend fun doConnect(profileId: String, options: CoreStartOptions) {
        val profile = profiles.byId(profileId)
        if (profile == null) {
            fail(profileId, ConnectionError.InvalidConfiguration("profile $profileId not found"))
            return
        }
        if (_state.value.isActive || _state.value is ConnectionState.Starting) {
            if (_state.value.profileIdOrNull == profileId) {
                log("Already connected to requested profile; ignoring")
                return
            }
            log("Switching profile: stopping current tunnel first")
            stopEverything()
        }

        activeProfile = profile
        activeOptions = options
        transition(ConnectionState.Starting(profileId))
        log("Starting tunnel for ${profile.toLogString()}")

        val tunProvider = try {
            tunnelHost.acquire(profile)
        } catch (e: CoreException) {
            fail(profileId, e.error)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(profileId, ConnectionError.Unknown("tunnel host: ${e.message}"))
            return
        }
        tun = tunProvider

        try {
            core.start(profile, options, tunProvider)
        } catch (e: CoreException) {
            tunnelHost.release()
            tun = null
            fail(profileId, e.error)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            tunnelHost.release()
            tun = null
            fail(profileId, ConnectionError.CoreFailure("start: ${e.message}"))
            return
        }

        transition(ConnectionState.Connecting(profileId))
        log("Core running; probing upstream")

        val probeError = probeWithRetries(policy.initialProbeAttempts)
        if (probeError == null) {
            transition(ConnectionState.Connected(profileId, clock.nowMs()))
            log("Connected")
        } else {
            stopEverything()
            fail(profileId, probeError)
        }
    }

    private suspend fun doDisconnect(reason: String) {
        if (_state.value is ConnectionState.Disconnected) return
        val id = _state.value.profileIdOrNull
        transition(ConnectionState.Stopping(id))
        log("Stopping ($reason)")
        stopEverything()
        transition(ConnectionState.Disconnected)
    }

    private suspend fun stopEverything() {
        try {
            core.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("core.stop failed: ${e.message}")
        }
        try {
            tunnelHost.release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("tunnelHost.release failed: ${e.message}")
        }
        tun = null
        _statistics.value = null
    }

    private suspend fun fail(profileId: String?, error: ConnectionError) {
        activeProfile = null
        _statistics.value = null
        _events.emit(ConnectionEvent(clock.nowMs(), "Failed: ${error.userMessageKey}", error))
        transition(ConnectionState.Error(profileId, error))
    }

    // -------------------------------------------------------- reactive inputs

    private suspend fun onNetworkEvent(event: NetworkEvent) {
        commandMutex.withLock {
            when (event) {
                is NetworkEvent.DefaultChanged -> {
                    val previous = currentNetworkKey
                    currentNetworkKey = event.networkKey
                    val s = _state.value
                    val waitingForNetwork = s is ConnectionState.Reconnecting && s.attempt == 0
                    val switched = previous != null && previous != event.networkKey && s is ConnectionState.Connected
                    if (waitingForNetwork || switched) {
                        log("Default network ${if (waitingForNetwork) "restored" else "changed"} (${previous ?: "none"} → ${event.networkKey}); reconnecting")
                        reconnectLoop(s.profileIdOrNull!!, reason = "network changed to ${event.transport}", restartCore = false)
                    }
                }
                NetworkEvent.Lost -> {
                    currentNetworkKey = null
                    val s = _state.value
                    if (s is ConnectionState.Connected) {
                        log("Default network lost; waiting for a new network")
                        transition(ConnectionState.Reconnecting(s.profileId, attempt = 0, reason = "network lost"))
                    }
                }
            }
        }
    }

    private suspend fun onCoreFatal(error: ConnectionError) {
        commandMutex.withLock {
            val s = _state.value
            val id = s.profileIdOrNull ?: return@withLock
            if (!s.isActive) return@withLock
            log("Core reported fatal error: $error")
            if (error.retryable) {
                reconnectLoop(id, reason = "core error", restartCore = true)
            } else {
                stopEverything()
                fail(id, error)
            }
        }
    }

    private suspend fun onRevoked() {
        commandMutex.withLock {
            val id = _state.value.profileIdOrNull
            if (_state.value is ConnectionState.Disconnected) return@withLock
            log("VPN revoked by system")
            stopEverything()
            fail(id, ConnectionError.VpnRevoked())
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * Attempts to bring the upstream back. Deterministic: attempt n waits
     * `policy.backoffFor(n)`, then (optionally restarts the core and) probes.
     */
    private suspend fun reconnectLoop(profileId: String, reason: String, restartCore: Boolean) {
        val profile = activeProfile ?: return
        for (attempt in 1..policy.maxReconnectAttempts) {
            transition(ConnectionState.Reconnecting(profileId, attempt, reason))
            delay(policy.backoffFor(attempt))
            val ok = try {
                if (restartCore) {
                    core.stop()
                    val t = tun ?: tunnelHost.acquire(profile).also { tun = it }
                    core.start(profile, activeOptions, t)
                } else {
                    core.onNetworkChanged()
                }
                probeOnce() == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("reconnect attempt $attempt failed: ${e.message}")
                false
            }
            if (ok) {
                transition(ConnectionState.Connected(profileId, clock.nowMs()))
                log("Reconnected after $attempt attempt(s)")
                return
            }
        }
        stopEverything()
        fail(profileId, ConnectionError.ConnectionTimeout("gave up after ${policy.maxReconnectAttempts} reconnect attempts ($reason)"))
    }

    private suspend fun probeWithRetries(attempts: Int): ConnectionError? {
        var last: ConnectionError? = null
        for (i in 1..attempts) {
            last = probeOnce() ?: return null
            if (i < attempts) delay(policy.backoffFor(i))
        }
        return last
    }

    private suspend fun probeOnce(): ConnectionError? = try {
        withTimeout(policy.probeTimeoutMs + 1_000) {
            val rtt = core.urlTest(policy.probeUrl, policy.probeTimeoutMs)
            log("Probe ok (${rtt}ms)")
        }
        null
    } catch (e: CancellationException) {
        if (e is kotlinx.coroutines.TimeoutCancellationException) {
            ConnectionError.ConnectionTimeout("probe exceeded ${policy.probeTimeoutMs}ms")
        } else {
            throw e
        }
    } catch (e: CoreException) {
        e.error
    } catch (e: Throwable) {
        ConnectionError.ConnectionTimeout("probe failed: ${e.message}")
    }

    private fun transition(next: ConnectionState) {
        _state.value = next
        _transitions.tryEmit(next)
    }

    private suspend fun log(message: String) {
        _events.emit(ConnectionEvent(clock.nowMs(), message))
    }
}
