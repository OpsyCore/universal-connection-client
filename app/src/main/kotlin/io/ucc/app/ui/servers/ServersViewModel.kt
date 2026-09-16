package io.ucc.app.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.app.data.SelectionStore
import io.ucc.app.data.ServerRepository
import io.ucc.app.data.ServerRepository.Companion.boundProfileId
import io.ucc.app.data.SubscriptionRefresher
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
)

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
) {
    val isEmpty: Boolean get() = totalCount == 0
    val nothingMatches: Boolean get() = totalCount > 0 && groups.all { it.rows.isEmpty() }
}

sealed class PendingDelete {
    data class Profiles(val ids: Set<String>) : PendingDelete()
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
) : ViewModel() {

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
        repo.groups, preferences.selectedProfileIdFlow, manager.state, combine(query, favoritesOnly, refreshing) { q, f, r -> Triple(q, f, r) }, local,
    ) { groups, selectedId, conn, (q, favOnly, busy), loc ->
        val activeId = conn.boundProfileId
        val needle = q.trim().lowercase()
        val total = groups.sumOf { it.profiles.size }
        val visible = groups.map { g ->
            val rows = g.profiles.asSequence()
                .filter { !favOnly || it.metadata.favorite }
                .filter { needle.isEmpty() || it.matches(needle) }
                .sortedWith(compareByDescending<ConnectionProfile> { it.metadata.favorite }.thenBy { it.name.lowercase() })
                .map { ServerRow(it, selected = it.id == selectedId, active = it.id == activeId) }
                .toList()
            ServerGroup(g.subscription, rows, refreshing = g.subscription?.id in busy)
        }
        ServersUiState(
            query = q, favoritesOnly = favOnly, groups = visible, totalCount = total,
            selectionMode = loc.selectionMode, checked = loc.checked, renaming = loc.renaming, confirmDelete = loc.confirmDelete,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    private fun ConnectionProfile.matches(needle: String): Boolean =
        name.lowercase().contains(needle) || address.lowercase().contains(needle) ||
            protocol.name.lowercase().contains(needle) || metadata.tags.any { it.lowercase().contains(needle) }

    // ---- search / filter ----
    fun onQueryChanged(q: String) { query.value = q }
    fun toggleFavoritesOnly() { favoritesOnly.value = !favoritesOnly.value }

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ServersViewModel(repo, refresher, preferences, manager) as T
    }
}
