package io.ucc.applogic

import io.ucc.core.platform.currentTimeMillis
import io.ucc.core.config.ConfigImporter
import io.ucc.core.config.ImportPlan
import io.ucc.core.config.ImportPlanner
import io.ucc.core.config.ImportReport
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionFetchResult
import io.ucc.core.config.subscription.SubscriptionFetcher
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-side orchestration of INPUT → detect → parse → validate →
 * normalize → plan → persist. Contains no parsing logic: it composes
 * `core:config` and the stores. Pure Kotlin so it is unit-testable with fakes.
 */
class ImportRepository(
    private val importer: ConfigImporter,
    private val planner: ImportPlanner,
    private val profiles: ProfileStore,
    private val subscriptions: SubscriptionStore,
    private val fetcher: SubscriptionFetcher,
    private val now: () -> Long = ::currentTimeMillis,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Text from paste / clipboard / QR / file. Runs on Default because base64 + JSON parsing can be sizeable. */
    suspend fun planFromText(text: String, source: ProfileSource): ImportPlan = withContext(parseDispatcher) {
        val report = importer.import(text, source)
        planner.plan(report, profiles.current())
    }

    /** Downloads the subscription and plans it; the subscription record is only persisted on confirm. */
    suspend fun planFromSubscription(url: String): SubscriptionPlan {
        val fetched = fetcher.fetch(url) // throws SubscriptionFetchError
        val subId = Subscription.idFor(url)
        val report: ImportReport = withContext(parseDispatcher) { importer.import(fetched.body, ProfileSource.Subscription(subId)) }
        val plan = planner.plan(report, profiles.current())
        return SubscriptionPlan(url = url.trim(), subscriptionId = subId, fetched = fetched, plan = plan)
    }

    /**
     * Persists the profiles the user ticked. Only savable items are accepted;
     * duplicates/invalid ones are ignored even if passed. Returns saved ids.
     * Never connects.
     */
    suspend fun commit(plan: ImportPlan, selectedIds: Set<String>, subscription: SubscriptionPlan? = null): CommitResult {
        val toSave = plan.items.filter { it.profile.id in selectedIds && it.status.isSavable }.map { it.profile }
        val stamped = toSave.map { p ->
            val groupId = subscription?.subscriptionId
            if (groupId == null) p else p.copy(metadata = p.metadata.copy(groupId = groupId))
        }
        if (stamped.isNotEmpty()) profiles.upsertAll(stamped)
        if (subscription != null) {
            subscriptions.upsert(
                Subscription(
                    id = subscription.subscriptionId,
                    url = subscription.url,
                    name = subscription.fetched.suggestedName?.takeIf { it.isNotBlank() } ?: subscription.url.removePrefix("https://").take(40),
                    addedAtEpochMs = subscriptions.byId(subscription.subscriptionId)?.addedAtEpochMs ?: now(),
                    lastFetchedAtEpochMs = now(),
                    lastInfo = subscription.fetched.info,
                    autoUpdate = subscriptions.byId(subscription.subscriptionId)?.autoUpdate ?: true,
                    updateIntervalHours = subscription.fetched.updateIntervalHours,
                ),
            )
        }
        return CommitResult(savedIds = stamped.map { it.id }, skipped = selectedIds.size - stamped.size)
    }
}

data class SubscriptionPlan(val url: String, val subscriptionId: String, val fetched: SubscriptionFetchResult, val plan: ImportPlan)

data class CommitResult(val savedIds: List<String>, val skipped: Int)
