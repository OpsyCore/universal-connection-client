package io.ucc.app.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.applogic.SelectionStore
import io.ucc.applogic.ServerRepository
import io.ucc.applogic.ServerRepository.Companion.boundProfileId
import io.ucc.applogic.SubscriptionRefresher
import io.ucc.core.smart.ConnectionTestResult
import io.ucc.core.smart.HealthCheckRunner
import io.ucc.core.smart.HealthStatus
import io.ucc.core.smart.ServerHealth
import io.ucc.core.smart.ServerHealthEvaluator
import io.ucc.core.smart.ServerHealthStore
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ServerRow(
    val profile: ConnectionProfile,
    val selected: Boolean,
    val active: Boolean,
    /** Persistent, fingerprint-keyed health record (empty when never observed). */
    val health: ServerHealth = ServerHealth.EMPTY,
    /** Derived from [health] against the current time and network. */
    val status: HealthStatus = HealthStatus.UNKNOWN,
    /** Result of a test started from this screen in this session; null = not tested here. */
    val lastTest: ConnectionTestResult? = null,
    val testing: Boolean = false,
    /** Same content as [ServersUiState.recommendedId]; true for at most one row. */
    val recommended: Boolean = false,
) {
    /**
     * Latest evidence says the server is down: a server-attributable failure in this session's test, or a
     * persisted OFFLINE status. Local problems (no network, cancelled, unsupported) never count.
     */
    val isFailed: Boolean
        get() {
            val t = lastTest
            if (t != null) return !t.success && t.failure?.attributableToServer == true
            return status == HealthStatus.OFFLINE
        }
}

data class ServerGroup(
    /** null = manual / ungrouped. */
    val subscription: Subscription?,
    val rows: List<ServerRow>,
    val refreshing: Boolean,
)

data class ServersUiState(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val groups: List<ServerGroup> = emptyList(),
    val totalCount: Int = 0,
    val selectionMode: Boolean = false,
    val checked: Set<String> = emptySet(),
    val renaming: ConnectionProfile? = null,
    val confirmDelete: PendingDelete? = null,
    val testingAll: Boolean = false,
    /** Profile Smart would pick right now with the stored evidence; null when there is not enough data. */
    val recommendedId: String? = null,
    /** v1.0.2: rows ordered by measured latency (best first). */
    val sortByLatency: Boolean = false,
    /** v1.0.2: rows with failing evidence are hidden; [hiddenCount] says how many. */
    val hideFailed: Boolean = false,
    val hiddenCount: Int = 0,
    /** Servers whose latest evidence says "down" (candidates for "Delete failed servers"); never the active or selected one. */
    val failedIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = totalCount == 0
    val nothingMatches: Boolean get() = totalCount > 0 && groups.all { it.rows.isEmpty() }
}

sealed class PendingDelete {
    data class Profiles(val ids: Set<String>) : PendingDelete()
    /** v1.0.2: bulk removal of servers that failed their latest test / are offline. */
    data class Failed(val ids: Set<String>) : PendingDelete()
    data class SubscriptionGroup(val subscription: Subscription, val memberCount: Int) : PendingDelete()
}

/** One-shot UI messages (snackbar / share sheet). Export text contains credentials: consumed once, never stored in state. */
sealed class ServersEffect {
    data class Deleted(val count: Int) : ServersEffect()
    data object DeleteBlockedActive : ServersEffect()
    data class Share(val text: String, val count: Int) : ServersEffect()
    data class Refreshed(val name: String, val added: Int, val updated: Int, val removed: Int) : ServersEffect()
    data class RefreshFailed(val name: String, val outcome: SubscriptionRefresher.Outcome) : ServersEffect()
}

class ServersViewModel(
    private val repo: ServerRepository,
    private val refresher: SubscriptionRefresher,
    private val preferences: SelectionStore,
    manager: ConnectionManager,
    private val runner: HealthCheckRunner,
    private val health: ServerHealthStore,
    private val recommendation: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null),
    private val transport: kotlinx.coroutines.flow.StateFlow<String?> = MutableStateFlow(null),
    private val now: () -> Long = System::currentTimeMillis,
    private val listPrefs: io.ucc.applogic.ServerListPrefs = io.ucc.applogic.InMemoryServerListPrefs(),
) : ViewModel() {

    private val reach = MutableStateFlow<Map<String, ConnectionTestResult>>(emptyMap())
    private val testing = MutableStateFlow<Set<String>>(emptySet())
    private var testAllJob: kotlinx.coroutines.Job? = null

    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val refreshing = MutableStateFlow<Set<String>>(emptySet())
    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val selectionMode: Boolean = false,
        val checked: Set<String> = emptySet(),
        val renaming: ConnectionProfile? = null,
        val confirmDelete: PendingDelete? = null,
    )

    private val _effects = MutableSharedFlow<ServersEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<ServersEffect> = _effects

    val state: StateFlow<ServersUiState> = combine(
        repo.groups, preferences.selectedProfileIdFlow, manager.state, combine(query, favoritesOnly, refreshing) { q, f, r -> Triple(q, f, r) },
        combine(local, reach, testing, health.all, combine(recommendation, transport, listPrefs.sortByLatencyFlow, listPrefs.hideFailedFlow) { rec, tr, srt, hide -> ListInputs(rec, tr, srt, hide) }) { l, r, t, h, li -> RowInputs(l, r, t, h, li) },
    ) { groups, selectedId, conn, (q, favOnly, busy), (loc, reachMap, testingIds, healthMap, li) ->
        val activeId = conn.boundProfileId
        val nowMs = now()
        val needle = q.trim().lowercase()
        val total = groups.sumOf { it.profiles.size }
        var hidden = 0
        val failed = LinkedHashSet<String>()
        val visible = groups.map { g ->
            val rows = g.profiles.asSequence()
                .filter { !favOnly || it.metadata.favorite }
                .filter { needle.isEmpty() || it.matches(needle) }
                .map {
                    val h = healthMap[it.fingerprint] ?: ServerHealth.EMPTY
                    ServerRow(
                        it, selected = it.id == selectedId, active = it.id == activeId,
                        health = h, status = ServerHealthEvaluator.status(h, nowMs, li.transport),
                        lastTest = reachMap[it.id], testing = it.id in testingIds, recommended = it.id == li.recommendedId,
                    )
                }
                .onEach { if (it.isFailed && !it.active && !it.selected) failed += it.profile.id }
                .filter { row -> if (li.hideFailed && row.isFailed && !row.active && !row.selected) { hidden++; false } else true }
                .sortedWith(if (li.sortByLatency) LATENCY_ORDER else DEFAULT_ORDER)
                .toList()
            ServerGroup(g.subscription, rows, refreshing = g.subscription?.id in busy)
        }
        ServersUiState(
            query = q, favoritesOnly = favOnly, groups = visible, totalCount = total,
            selectionMode = loc.selectionMode, checked = loc.checked, renaming = loc.renaming, confirmDelete = loc.confirmDelete,
            testingAll = testAllJob?.isActive == true,
            recommendedId = li.recommendedId,
            sortByLatency = li.sortByLatency, hideFailed = li.hideFailed, hiddenCount = hidden, failedIds = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    private data class ListInputs(val recommendedId: String?, val transport: String?, val sortByLatency: Boolean, val hideFailed: Boolean)
    private data class RowInputs(
        val local: LocalState, val reach: Map<String, ConnectionTestResult>, val testing: Set<String>,
        val health: Map<String, ServerHealth>, val list: ListInputs,
    )



    private fun ConnectionProfile.matches(needle: String): Boolean =
        name.lowercase().contains(needle) || address.lowercase().contains(needle) ||
            protocol.name.lowercase().contains(needle) || metadata.tags.any { it.lowercase().contains(needle) }

    // ---- search / filter ----
    fun onQueryChanged(q: String) { query.value = q }

    private val singleTests = HashMap<String, kotlinx.coroutines.Job>()

    /**
     * Connection test of one server (TCP reach + latency; see
     * [io.ucc.core.smart.TcpConnectionTester] for what it does and does not
     * prove). Tapping again while running cancels. Never connects.
     */
    fun testReachability(id: String) {
        singleTests.remove(id)?.let { if (it.isActive) { it.cancel(); return } }
        val p = repo.byId(id) ?: return
        singleTests[id] = viewModelScope.launch {
            testing.value += id
            try { reach.value += id to runner.test(p) } finally { testing.value -= id; singleTests.remove(id) }
        }
    }

    /** Tests every currently visible server with bounded parallelism; a second call while running cancels. */
    fun testVisibleReachability() {
        testAllJob?.let { if (it.isActive) { it.cancel(); testing.value = emptySet(); return } }
        val visible = state.value.groups.flatMap { g -> g.rows.map { it.profile } }
        if (visible.isEmpty()) return
        testAllJob = viewModelScope.launch {
            testing.value = visible.map { it.id }.toSet()
            try {
                runner.testAll(visible) { p, r -> reach.value += p.id to r; testing.value -= p.id }
            } finally { testing.value = emptySet() }
        }
    }
    fun toggleFavoritesOnly() { favoritesOnly.value = !favoritesOnly.value }
    fun setSortByLatency(v: Boolean) { listPrefs.sortByLatency = v }
    fun setHideFailed(v: Boolean) { listPrefs.hideFailed = v }

    /** Asks to delete every server in [ServersUiState.failedIds]; the active/selected one is never included. Confirmed via the delete dialog. */
    fun requestDeleteFailed() {
        val ids = state.value.failedIds
        if (ids.isEmpty()) return
        local.value = local.value.copy(confirmDelete = PendingDelete.Failed(ids))
    }

    // ---- single-item actions ----
    fun select(id: String) { preferences.selectedProfileId = id }

    fun toggleFavorite(id: String) {
        val p = repo.byId(id) ?: return
        viewModelScope.launch { repo.setFavorite(id, !p.metadata.favorite) }
    }

    fun startRename(id: String) { local.value = local.value.copy(renaming = repo.byId(id)) }
    fun cancelRename() { local.value = local.value.copy(renaming = null) }
    fun confirmRename(newName: String) {
        val target = local.value.renaming ?: return
        local.value = local.value.copy(renaming = null)
        viewModelScope.launch { repo.rename(target.id, newName) }
    }

    fun requestDelete(ids: Set<String>) {
        if (ids.isEmpty()) return
        local.value = local.value.copy(confirmDelete = PendingDelete.Profiles(ids))
    }

    fun requestDeleteSubscription(sub: Subscription) {
        val members = state.value.groups.firstOrNull { it.subscription?.id == sub.id }?.rows?.size ?: 0
        local.value = local.value.copy(confirmDelete = PendingDelete.SubscriptionGroup(sub, members))
    }

    fun cancelDelete() { local.value = local.value.copy(confirmDelete = null) }

    fun confirmDelete() {
        val pending = local.value.confirmDelete ?: return
        local.value = local.value.copy(confirmDelete = null, selectionMode = false, checked = emptySet())
        viewModelScope.launch {
            val r = when (pending) {
                is PendingDelete.Profiles -> repo.delete(pending.ids)
                is PendingDelete.Failed -> repo.delete(pending.ids)
                is PendingDelete.SubscriptionGroup -> repo.deleteSubscription(pending.subscription.id)
            }
            if (r.blockedActive) _effects.tryEmit(ServersEffect.DeleteBlockedActive)
            if (r.deleted > 0 || (pending is PendingDelete.SubscriptionGroup && !r.blockedActive)) _effects.tryEmit(ServersEffect.Deleted(r.deleted))
        }
    }

    /** Export is explicit and one-shot; the link text goes straight to the share sheet, not into UI state. */
    fun share(ids: Set<String>) {
        if (ids.isEmpty()) return
        val e = repo.exportLinks(ids)
        if (e.exported > 0) _effects.tryEmit(ServersEffect.Share(e.text, e.exported))
        exitSelection()
    }

    // ---- multi-select ----
    fun enterSelection(firstId: String) { local.value = local.value.copy(selectionMode = true, checked = setOf(firstId)) }
    fun exitSelection() { local.value = local.value.copy(selectionMode = false, checked = emptySet()) }
    fun toggleChecked(id: String) {
        val cur = local.value.checked
        val next = if (id in cur) cur - id else cur + id
        local.value = local.value.copy(checked = next, selectionMode = next.isNotEmpty())
    }
    fun checkAllVisible() {
        local.value = local.value.copy(selectionMode = true, checked = state.value.groups.flatMap { g -> g.rows.map { it.profile.id } }.toSet())
    }

    // ---- subscriptions ----
    fun refresh(sub: Subscription) {
        if (sub.id in refreshing.value) return
        refreshing.value = refreshing.value + sub.id
        viewModelScope.launch {
            try {
                when (val o = refresher.refresh(sub.id)) {
                    is SubscriptionRefresher.Outcome.Updated -> _effects.tryEmit(ServersEffect.Refreshed(o.subscription.name, o.result.added, o.result.updated, o.result.removed))
                    else -> _effects.tryEmit(ServersEffect.RefreshFailed(sub.name, o))
                }
            } finally {
                refreshing.value = refreshing.value - sub.id
            }
        }
    }

    fun setAutoUpdate(sub: Subscription, enabled: Boolean) { viewModelScope.launch { repo.setAutoUpdate(sub.id, enabled) } }

    class Factory(
        private val repo: ServerRepository,
        private val refresher: SubscriptionRefresher,
        private val preferences: SelectionStore,
        private val manager: ConnectionManager,
        private val smart: io.ucc.applogic.SmartConnectionCoordinator,
        private val health: ServerHealthStore,
        private val runner: HealthCheckRunner,
        private val listPrefs: io.ucc.applogic.ServerListPrefs,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ServersViewModel(
            repo, refresher, preferences, manager, runner, health,
            recommendation = smart.recommendedId,
            transport = smart.transport,
            listPrefs = listPrefs,
        ) as T
    }
}

private val DEFAULT_ORDER: Comparator<ServerRow> = compareByDescending<ServerRow> { it.profile.metadata.favorite }.thenBy { it.profile.name.lowercase() }
/** Measured servers first (session test beats stored EMA), then untested, then failed; name breaks ties. Deterministic. */
private val LATENCY_ORDER: Comparator<ServerRow> = compareBy<ServerRow> { it.sortRank }.thenBy { it.sortLatency }.thenBy { it.profile.name.lowercase() }

/** Sort bucket: 0 = has a latency, 1 = untested / unknown, 2 = failed. */
private val ServerRow.sortRank: Int get() = when { sortLatency != Long.MAX_VALUE -> 0; isFailed -> 2; else -> 1 }
private val ServerRow.sortLatency: Long get() = lastTest?.takeIf { it.success }?.latencyMs ?: (if (lastTest == null && status != HealthStatus.OFFLINE) health.rollingLatencyMs else null) ?: Long.MAX_VALUE
