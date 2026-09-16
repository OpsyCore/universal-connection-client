package io.ucc.app.data

import io.ucc.core.config.export.ShareLinkExporter
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Server management use-cases (Servers screen): rename, favorite, delete,
 * export, grouping. Pure Kotlin over the store contracts, so it is unit-tested
 * with fakes. Contains no parsing and no network I/O.
 *
 * Edits are recorded in metadata so that subscription refresh can respect them
 * (`userRenamed`, `favorite`); see [io.ucc.core.config.subscription.SubscriptionMerger].
 */
class ServerRepository(
    private val profiles: ProfileStore,
    private val subscriptions: SubscriptionStore,
    private val manager: ConnectionManager,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Stable, display-oriented grouping of the store: `null` group = manual/ungrouped profiles. */
    data class Group(val subscription: Subscription?, val profiles: List<ConnectionProfile>)

    val groups: Flow<List<Group>> = combine(profiles.profiles, subscriptions.all) { ps, subs ->
        val byGroup = ps.groupBy { it.metadata.groupId }
        val subById = subs.associateBy { it.id }
        val known = subs.map { s -> Group(s, byGroup[s.id].orEmpty()) }
        // Profiles whose subscription record vanished (should not happen) are shown as ungrouped rather than hidden.
        val orphans = byGroup.filterKeys { it != null && it !in subById }.values.flatten()
        val manual = Group(null, byGroup[null].orEmpty() + orphans)
        listOf(manual).filter { it.profiles.isNotEmpty() } + known
    }

    suspend fun rename(id: String, newName: String): Boolean {
        val p = profiles.current().firstOrNull { it.id == id } ?: return false
        val name = newName.trim()
        if (name.isEmpty() || name == p.name) return false
        profiles.upsertAll(listOf(p.copy(name = name, metadata = p.metadata.copy(userRenamed = true, updatedAtEpochMs = now()))))
        return true
    }

    suspend fun setFavorite(id: String, favorite: Boolean): Boolean {
        val p = profiles.current().firstOrNull { it.id == id } ?: return false
        if (p.metadata.favorite == favorite) return false
        profiles.upsertAll(listOf(p.copy(metadata = p.metadata.copy(favorite = favorite, updatedAtEpochMs = now()))))
        return true
    }

    /**
     * Deletes profiles. The profile that is currently active (any non-Disconnected
     * state) is refused — the UI must disconnect first — so the tunnel never
     * runs on a profile that no longer exists.
     */
    suspend fun delete(ids: Collection<String>): DeleteResult {
        val active = manager.state.value.profileIdOrNull
        val (blocked, ok) = ids.toSet().partition { it == active }
        if (ok.isNotEmpty()) profiles.deleteAll(ok)
        return DeleteResult(deleted = ok.size, blockedActive = blocked.isNotEmpty())
    }

    /** Removes the subscription record and every profile that belongs to it (favorites included — the user asked for the group to go). */
    suspend fun deleteSubscription(subscriptionId: String): DeleteResult {
        val members = profiles.current().filter { it.metadata.groupId == subscriptionId }.map { it.id }
        val r = delete(members)
        if (!r.blockedActive) subscriptions.delete(subscriptionId)
        return r
    }

    suspend fun setAutoUpdate(subscriptionId: String, enabled: Boolean) {
        val s = subscriptions.byId(subscriptionId) ?: return
        if (s.autoUpdate != enabled) subscriptions.upsert(s.copy(autoUpdate = enabled))
    }

    /**
     * Share link(s) for the given ids in store order. **Contains credentials.**
     * Profiles without a link form (none today) are skipped and counted.
     */
    fun exportLinks(ids: Collection<String>): Export {
        val wanted = ids.toSet()
        val selected = profiles.current().filter { it.id in wanted }
        val links = selected.mapNotNull { ShareLinkExporter.export(it) }
        return Export(text = links.joinToString("\n"), exported = links.size, skipped = selected.size - links.size)
    }

    fun byId(id: String): ConnectionProfile? = profiles.current().firstOrNull { it.id == id }

    data class DeleteResult(val deleted: Int, val blockedActive: Boolean)
    data class Export(val text: String, val exported: Int, val skipped: Int)
}
