package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription

/**
 * The pre-installed "public free servers" subscription was **withdrawn** before the 1.0.3 release (publisher
 * decision: the public aggregations were of no practical use). Nothing is seeded any more.
 *
 * What remains is the upgrade cleanup: installs that ran a 1.0.3 release candidate may still carry one of the
 * lists seeded then; [toRemove] names those so `AppGraph` can delete them (with their servers) once. Everything
 * the user added themselves is untouched, and the active profile is never deleted.
 */
object DefaultSubscription {
    /** Every URL a 1.0.3 release candidate seeded. Kept only so the records can be recognised and removed. */
    val WITHDRAWN_URLS: List<String> = listOf(
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
        "https://raw.githubusercontent.com/yebekhe/TV2Ray/main/subscriptions/v2ray/vless",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vless.txt",
    )

    val WITHDRAWN_IDS: List<String> get() = WITHDRAWN_URLS.map(Subscription::idFor)

    /** Subscriptions from the release candidates that are actually present and should be deleted. */
    fun toRemove(existingIds: Collection<String>): List<String> = WITHDRAWN_IDS.filter { it in existingIds }
}
