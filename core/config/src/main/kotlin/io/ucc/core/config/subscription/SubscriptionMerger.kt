package io.ucc.core.config.subscription

import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileMetadata
import io.ucc.core.model.ProfileSource

/**
 * Three-way merge of a freshly fetched subscription body into the profiles that
 * already belong to that subscription group.
 *
 * Identity is the content [ConnectionProfile.fingerprint], never the name.
 *
 * Rules (documented in docs/SUBSCRIPTIONS.md):
 *  - **Matched** (same fingerprint already in the group): the stored profile is
 *    kept — same id, same `createdAt`, favorite flag, routing/DNS overrides and
 *    `lastUsedAt`. The upstream name is applied only if the user has *not*
 *    renamed it; tags and `rawSource` follow upstream. If nothing changed the
 *    row is not rewritten.
 *  - **New** (fingerprint not present anywhere): inserted with the subscription
 *    as source and group.
 *  - **Duplicate of a profile outside the group** (e.g. a manually added
 *    server): skipped, so a profile is never stored twice.
 *  - **Gone** (in the group but no longer upstream): deleted, unless the user
 *    marked it as favorite or its id is in `pinnedIds` (e.g. the profile that is
 *    connected right now) — those are kept and reported in [Result.keptFavorites].
 */
public class SubscriptionMerger {

    public data class Result(
        val toUpsert: List<ConnectionProfile>,
        val toDeleteIds: List<String>,
        val added: Int,
        val updated: Int,
        val unchanged: Int,
        val removed: Int,
        val keptFavorites: Int,
        val skippedDuplicates: Int,
    ) {
        val hasChanges: Boolean get() = toUpsert.isNotEmpty() || toDeleteIds.isNotEmpty()
    }

    public fun merge(
        subscriptionId: String,
        existingInGroup: List<ConnectionProfile>,
        existingElsewhere: List<ConnectionProfile>,
        fetched: List<ConnectionProfile>,
        nowMs: Long,
        pinnedIds: Set<String> = emptySet(),
    ): Result {
        val groupByFp = LinkedHashMap<String, ConnectionProfile>()
        existingInGroup.forEach { groupByFp.putIfAbsent(it.fingerprint, it) }
        val elsewhereFps = existingElsewhere.mapTo(HashSet()) { it.fingerprint }

        val upserts = ArrayList<ConnectionProfile>()
        var added = 0; var updated = 0; var unchanged = 0; var skipped = 0
        val seen = HashSet<String>()

        for (incoming in fetched) {
            val fp = incoming.fingerprint
            if (!seen.add(fp)) continue // duplicate inside the body
            val current = groupByFp[fp]
            if (current != null) {
                val merged = current.copy(
                    name = if (current.metadata.userRenamed) current.name else incoming.name,
                    metadata = current.metadata.copy(
                        source = ProfileSource.Subscription(subscriptionId),
                        groupId = subscriptionId,
                        tags = incoming.metadata.tags,
                        rawSource = incoming.metadata.rawSource,
                    ),
                )
                if (merged == current) {
                    unchanged++
                } else {
                    updated++
                    upserts += merged.copy(metadata = merged.metadata.copy(updatedAtEpochMs = nowMs))
                }
            } else if (fp in elsewhereFps) {
                skipped++
            } else {
                added++
                upserts += incoming.copy(
                    metadata = stamp(incoming.metadata, subscriptionId, nowMs),
                )
            }
        }

        val deletes = ArrayList<String>()
        var keptFavorites = 0
        for ((fp, current) in groupByFp) {
            if (fp in seen) continue
            if (current.metadata.favorite || current.id in pinnedIds) keptFavorites++ else deletes += current.id
        }
        // Profiles that share a fingerprint inside the stored group (should not happen, but be robust): drop the extras.
        val extras = existingInGroup.filter { groupByFp[it.fingerprint] !== it }.map { it.id }
        deletes += extras

        return Result(
            toUpsert = upserts,
            toDeleteIds = deletes,
            added = added,
            updated = updated,
            unchanged = unchanged,
            removed = deletes.size - extras.size,
            keptFavorites = keptFavorites,
            skippedDuplicates = skipped,
        )
    }

    private fun stamp(m: ProfileMetadata, subscriptionId: String, nowMs: Long): ProfileMetadata = m.copy(
        source = ProfileSource.Subscription(subscriptionId),
        groupId = subscriptionId,
        createdAtEpochMs = if (m.createdAtEpochMs == 0L) nowMs else m.createdAtEpochMs,
        updatedAtEpochMs = nowMs,
    )
}
