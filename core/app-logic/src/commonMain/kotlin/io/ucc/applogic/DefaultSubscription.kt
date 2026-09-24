package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription

/**
 * The public free-server list that is seeded **once**, on first launch (v1.0.3). Product decision by the
 * publisher: users get connection options immediately. Consequences, stated plainly:
 *  - the list is a third-party aggregation of anonymous public servers (no guarantee of availability,
 *    speed or trustworthiness; traffic through them is visible to whoever runs them);
 *  - fetching it is one HTTPS GET to GitHub on first launch and then per the normal auto-update rules;
 *  - it is an ordinary subscription afterwards: the user can rename, disable auto-update or delete it,
 *    and it is never re-seeded (the seeded flag persists), so a deletion sticks.
 * Nothing here connects automatically; Smart selection treats these servers like any other.
 */
object DefaultSubscription {
    const val URL: String = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"

    /** Same id `ImportRepository` would derive if the user added this URL by hand, so there can never be two. */
    val ID: String get() = Subscription.idFor(URL)

    /** Record to persist on first launch; [name] comes from string resources (fa/en). */
    fun record(name: String, nowEpochMs: Long): Subscription = Subscription(id = ID, url = URL, name = name, addedAtEpochMs = nowEpochMs, autoUpdate = true)

    /**
     * Decides whether to seed: only when the app has never seeded before AND the subscription does not
     * already exist (e.g. restored data or added manually). Pure; unit-tested.
     */
    fun shouldSeed(alreadySeeded: Boolean, existingIds: Collection<String>): Boolean = !alreadySeeded && ID !in existingIds
}
