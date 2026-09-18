package io.ucc.applogic

import io.ucc.core.platform.currentTimeMillis
import io.ucc.core.config.ConfigImporter
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.config.subscription.SubscriptionFetcher
import io.ucc.core.config.subscription.SubscriptionMerger
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Re-downloads a stored subscription and merges the result into the profile
 * store without destroying user edits (see [SubscriptionMerger]).
 *
 * Used by the manual "Refresh" action on the Servers screen and by the
 * periodic WorkManager job. Refreshes are serialised: two overlapping
 * refreshes of the same or different subscriptions never interleave writes.
 *
 * On failure nothing in the profile store changes; only `lastError` on the
 * subscription record is updated (redacted: never the URL or body).
 */
class SubscriptionRefresher(
    private val importer: ConfigImporter,
    private val fetcher: SubscriptionFetcher,
    private val profiles: ProfileStore,
    private val subscriptions: SubscriptionStore,
    private val manager: ConnectionManager,
    private val merger: SubscriptionMerger = SubscriptionMerger(),
    private val now: () -> Long = ::currentTimeMillis,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    sealed class Outcome {
        data class Updated(val result: SubscriptionMerger.Result, val subscription: Subscription) : Outcome()
        data class Failed(val subscriptionId: String, val error: SubscriptionFetchError) : Outcome()
        /** Body downloaded but nothing parsed. Treated as an error: an empty body would wipe the group. */
        data class EmptyBody(val subscriptionId: String, val unreadable: Int) : Outcome()
        data object NotFound : Outcome()
    }

    private val lock = Mutex()

    suspend fun refresh(subscriptionId: String): Outcome = lock.withLock {
        val sub = subscriptions.byId(subscriptionId) ?: return Outcome.NotFound
        val fetched = try {
            fetcher.fetch(sub.url)
        } catch (e: SubscriptionFetchError) {
            subscriptions.upsert(sub.copy(lastError = e.redactedLabel()))
            return Outcome.Failed(subscriptionId, e)
        }
        val report = withContext(parseDispatcher) { importer.import(fetched.body, ProfileSource.Subscription(subscriptionId)) }
        if (report.profiles.isEmpty()) {
            subscriptions.upsert(sub.copy(lastError = "empty:${report.failures.size}"))
            return Outcome.EmptyBody(subscriptionId, report.failures.size)
        }
        val all = profiles.current()
        val (inGroup, elsewhere) = all.partition { it.metadata.groupId == subscriptionId }
        val pinned = setOfNotNull(manager.state.value.boundProfileId)
        val result = merger.merge(subscriptionId, inGroup, elsewhere, report.profiles, now(), pinnedIds = pinned)
        if (result.hasChanges) profiles.apply(result.toUpsert, result.toDeleteIds)
        val updated = sub.copy(
            name = if (sub.name.isBlank()) fetched.suggestedName ?: sub.name else sub.name,
            lastFetchedAtEpochMs = now(),
            lastInfo = fetched.info ?: sub.lastInfo,
            updateIntervalHours = fetched.updateIntervalHours ?: sub.updateIntervalHours,
            lastError = null,
        )
        subscriptions.upsert(updated)
        return Outcome.Updated(result, updated)
    }

    /** Refreshes every subscription with auto-update on. Returns per-subscription outcomes; never throws. */
    suspend fun refreshAllDue(minIntervalMs: Long): List<Outcome> {
        val t = now()
        return subscriptions.all.value
            .filter { it.autoUpdate }
            .filter { s ->
                val interval = s.updateIntervalHours?.let { it * 3_600_000L }?.coerceAtLeast(minIntervalMs) ?: minIntervalMs
                val last = s.lastFetchedAtEpochMs ?: 0L
                t - last >= interval
            }
            .map { s -> try { refresh(s.id) } catch (e: Exception) { Outcome.Failed(s.id, SubscriptionFetchError.Network(e::class.simpleName ?: "Exception")) } }
    }

    private fun SubscriptionFetchError.redactedLabel(): String = when (this) {
        is SubscriptionFetchError.InvalidUrl -> "invalid_url"
        is SubscriptionFetchError.Http -> "http:$code"
        is SubscriptionFetchError.TooLarge -> "too_large"
        is SubscriptionFetchError.Network -> "network"
    }
}
