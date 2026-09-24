package io.ucc.applogic

import io.ucc.core.platform.currentTimeMillis
import io.ucc.core.config.CapabilityCheck
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.engine.manager.NetworkEvent
import io.ucc.core.engine.manager.NetworkMonitor
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.smart.Candidate
import io.ucc.core.smart.ConnectionTestResult
import io.ucc.core.smart.HealthCheckRunner
import io.ucc.core.smart.HealthStatus
import io.ucc.core.smart.Selection
import io.ucc.core.smart.ServerHealth
import io.ucc.core.smart.ServerHealthEvaluator
import io.ucc.core.smart.ServerHealthStore
import io.ucc.core.smart.SmartFailoverPolicy
import io.ucc.core.smart.SmartServerSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app-side glue between the pure `:core:smart` policy and the existing
 * ConnectionManager. It owns no connection state: it *observes* the manager's
 * state machine and *issues* `connect()` commands, exactly like a user would.
 *
 * Responsibilities:
 *  - Smart connect: pick a candidate (testing first when there is no fresh
 *    data), then `manager.connect(id)`.
 *  - Evidence: a tunnel reaching Connected is recorded as a success for that
 *    server; a terminal Error with a server-related cause is recorded as a
 *    failure. Manual connections feed the same store, so Smart learns from
 *    everything the user does.
 *  - Failover (Smart sessions only): when the manager ends in Error after its
 *    own same-server reconnect attempts, ask [SmartFailoverPolicy] for the
 *    next candidate. Bounded per session; a user disconnect ends the session.
 *  - Hygiene: health records whose fingerprint no longer exists in the profile
 *    store are pruned whenever the profile list changes (covers subscription
 *    refresh and deletes without touching either code path).
 */
class SmartConnectionCoordinator(
    private val scope: CoroutineScope,
    private val manager: ConnectionManager,
    private val profiles: ProfileStore,
    private val health: ServerHealthStore,
    private val runner: HealthCheckRunner,
    private val capabilities: CapabilityCheck,
    private val selection: SelectionStore,
    networkMonitor: NetworkMonitor,
    private val selector: SmartServerSelector = SmartServerSelector(),
    private val failover: SmartFailoverPolicy = SmartFailoverPolicy(selector),
    private val now: () -> Long = ::currentTimeMillis,
) {
    /** What Smart is doing right now, for the Home hero card. */
    sealed class Phase {
        data object Idle : Phase()
        /** Cold start: testing [count] servers before choosing. */
        data class Measuring(val count: Int) : Phase()
        data class Chosen(val profileId: String) : Phase()
        data class FailingOver(val fromId: String, val toId: String, val switchNumber: Int) : Phase()
        data object NoHealthyServer : Phase()
        data object NoCandidates : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /** Transport of the current default network, kept for network-aware staleness. */
    val transport = MutableStateFlow<String?>(null)

    private val lock = Mutex()
    private var session: SmartFailoverPolicy.Session? = null
    private var connectJob: Job? = null
    /**
     * Failover connect that has been handed to the manager but not yet observed
     * as Starting. If the user disconnects in that window, the manager's
     * disconnect() runs first (state is still Error, nothing to stop) and the
     * queued connect would then bring up a tunnel the user just cancelled. We
     * remember the id and disconnect again as soon as it starts.
     */
    private var pendingFailoverId: String? = null
    private var abortIfStarts: String? = null
    private var lastObserved: ConnectionState = ConnectionState.Disconnected

    /** Ranked view of every profile for the Servers/Smart screens. Recomputed on any input change. */
    val candidates: kotlinx.coroutines.flow.Flow<List<Pair<Candidate, HealthStatus>>> =
        combine(profiles.profiles, health.all, transport) { ps, h, t ->
            val n = now()
            ps.map { p -> Candidate(p, h[p.fingerprint] ?: ServerHealth.EMPTY, capabilities.isSupported(p)) }
                .map { it to ServerHealthEvaluator.status(it.health, n, t) }
        }

    /** Id Smart would pick with the evidence at hand; null when it would need to measure first or nothing is healthy. */
    val recommendedId: kotlinx.coroutines.flow.Flow<String?> = candidates.map { list ->
        (selector.select(list.map { it.first }, now(), transport.value) as? Selection.Chosen)?.candidate?.profile?.id
    }.distinctUntilChanged()

    init {
        networkMonitor.events.onEach { e ->
            transport.value = when (e) { is NetworkEvent.DefaultChanged -> e.transport; NetworkEvent.Lost -> null }
        }.launchIn(scope)
        manager.transitions.onEach { onState(it) }.launchIn(scope)
        // Prune on membership change only (fingerprint set), not on every rename/favourite.
        // An empty set is skipped: before the encrypted store has loaded the list is empty too,
        // and wiping history there would destroy it on every app start.
        profiles.profiles.map { ps -> ps.map { it.fingerprint }.toSet() }.distinctUntilChanged()
            .onEach { live -> if (live.isNotEmpty()) health.prune(live) }
            .launchIn(scope)
    }

    fun select(nowMs: Long = now()): Selection = selector.select(currentCandidates(), nowMs, transport.value)

    /** Smart connect entry point (VPN permission already granted by the caller). Cancels a previous cold-start measurement. */
    fun connectSmart() {
        connectJob?.cancel()
        connectJob = scope.launch {
            lock.withLock { session = null }
            var sel = select()
            if (sel is Selection.NeedsMeasurement) {
                _phase.value = Phase.Measuring(sel.toTest.size)
                runner.testAll(sel.toTest.map { it.profile })
                sel = select()
            }
            when (sel) {
                is Selection.Chosen -> start(sel.candidate.profile.id)
                is Selection.AllUnhealthy -> _phase.value = Phase.NoHealthyServer
                Selection.NoCandidates -> _phase.value = Phase.NoCandidates
                is Selection.NeedsMeasurement -> _phase.value = Phase.NoHealthyServer // tests ran and still nothing fresh (e.g. all UDP-only)
            }
        }
    }

    /** The user chose a specific server: not a Smart session, but the outcome still feeds health. */
    fun connectManual(profileId: String) {
        connectJob?.cancel()
        scope.launch { lock.withLock { session = null }; _phase.value = Phase.Idle; manager.connect(profileId) }
    }

    fun disconnect() {
        connectJob?.cancel()
        scope.launch {
            lock.withLock { session = null; abortIfStarts = pendingFailoverId; pendingFailoverId = null }
            _phase.value = Phase.Idle
            manager.disconnect()
        }
    }

    fun dismissPhase() { if (_phase.value is Phase.NoHealthyServer || _phase.value is Phase.NoCandidates) _phase.value = Phase.Idle }

    private suspend fun start(profileId: String) {
        lock.withLock { session = SmartFailoverPolicy.Session(profileId) }
        _phase.value = Phase.Chosen(profileId)
        manager.connect(profileId)
    }

    private suspend fun onState(s: ConnectionState) {
        val prev = lastObserved
        lastObserved = s
        if (s is ConnectionState.Starting) {
            val abort = lock.withLock {
                if (pendingFailoverId == s.profileId) pendingFailoverId = null
                if (abortIfStarts == s.profileId) { abortIfStarts = null; true } else { abortIfStarts = null; false }
            }
            if (abort) { manager.disconnect(); return }
        }
        when (s) {
            is ConnectionState.Connected -> if (prev !is ConnectionState.Connected) recordFor(s.profileId, ConnectionTestResult.TUNNEL_UP)
            is ConnectionState.Error -> {
                val id = s.profileId
                val failure = SmartFailoverPolicy.toTestFailure(s.error)
                val duplicate = prev is ConnectionState.Error && prev.profileId == id && prev.error === s.error
                if (id != null && !duplicate) recordFor(id, ConnectionTestResult.failed(failure))
                maybeFailover(s)
            }
            ConnectionState.Disconnected -> if (prev is ConnectionState.Stopping) lock.withLock { session = null }
            else -> Unit
        }
    }

    private suspend fun recordFor(profileId: String, r: ConnectionTestResult) {
        val p = profiles.current().firstOrNull { it.id == profileId } ?: return
        health.update(p.fingerprint) { it.record(r, now(), transport.value) }
    }

    private suspend fun maybeFailover(s: ConnectionState.Error) {
        val decision = lock.withLock {
            val ses = session ?: return
            if (ses.tried.last() != s.profileId) return // error belongs to something else
            val ranked = (select() as? Selection.Chosen)?.ranked ?: (select() as? Selection.AllUnhealthy)?.ranked ?: emptyList()
            failover.onTerminalFailure(selection.smartMode, ses, s.error, ranked, now(), transport.value)
        }
        when (decision) {
            is SmartFailoverPolicy.Decision.Switch -> {
                val ses = session ?: return
                _phase.value = Phase.FailingOver(fromId = s.profileId ?: "", toId = decision.to.profile.id, switchNumber = ses.switches)
                lock.withLock { pendingFailoverId = decision.to.profile.id }
                manager.connect(decision.to.profile.id)
            }
            is SmartFailoverPolicy.Decision.GiveUp -> {
                if (decision.reason != SmartFailoverPolicy.Reason.NOT_SMART_MODE) lock.withLock { session = null }
                if (decision.reason == SmartFailoverPolicy.Reason.NO_CANDIDATE || decision.reason == SmartFailoverPolicy.Reason.SWITCH_LIMIT) _phase.value = Phase.NoHealthyServer
            }
        }
    }

    private fun currentCandidates(): List<Candidate> = profiles.current().map { p ->
        Candidate(p, health.get(p.fingerprint), capabilities.isSupported(p))
    }

    /** Per-server test used by the Servers screen; goes through the shared runner so results land in the store. */
    suspend fun test(profile: ConnectionProfile): ConnectionTestResult = runner.test(profile)
    suspend fun testAll(profiles: List<ConnectionProfile>, onEach: suspend (ConnectionProfile, ConnectionTestResult) -> Unit) = runner.testAll(profiles, onEach)
}
