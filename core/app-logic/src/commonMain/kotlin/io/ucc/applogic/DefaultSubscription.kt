package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.Transport

/**
 * The public free-server list that is seeded **once per [SEED_VERSION]** (v1.0.3). Product decision by the
 * publisher: users get connection options immediately. Consequences, stated plainly:
 *  - the list is a third-party aggregation of anonymous public servers (no guarantee of availability,
 *    speed or trustworthiness; traffic through them is visible to whoever runs them);
 *  - fetching it is one HTTPS GET to GitHub on first launch and then per the normal auto-update rules;
 *  - it is an ordinary subscription afterwards: the user can rename, disable auto-update or delete it,
 *    and it is not re-seeded for the same [SEED_VERSION], so a deletion sticks.
 * Nothing here connects automatically; Smart selection treats these servers like any other.
 *
 * Curation ([curate]) applies to THIS subscription only — never to subscriptions the user adds:
 * VLESS/VMess only (Trojan, Shadowsocks, SOCKS, HTTP, Hysteria, TUIC, WireGuard dropped), one entry per
 * address:port, ordered REALITY → TLS + WS/gRPC → TLS → plain, capped at [MAX_SERVERS]. "Working" cannot be
 * known at import time; the real delay test decides that afterwards.
 */
object DefaultSubscription {
    /** Bump when URL or curation changes so existing installs are re-seeded (old list removed, new one added). */
    const val SEED_VERSION: Int = 2
    const val URL: String = "https://raw.githubusercontent.com/yebekhe/TV2Ray/main/subscriptions/v2ray/vless"
    /** Pre-v2 URL; its subscription (and member servers) are removed on upgrade. */
    const val LEGACY_URL_V1: String = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"
    const val MAX_SERVERS: Int = 25

    val ID: String get() = Subscription.idFor(URL)
    val LEGACY_IDS: List<String> get() = listOf(Subscription.idFor(LEGACY_URL_V1))

    private val ALLOWED = setOf(Protocol.VLESS, Protocol.VMESS)

    fun record(name: String, nowEpochMs: Long): Subscription = Subscription(id = ID, url = URL, name = name, addedAtEpochMs = nowEpochMs, autoUpdate = true)

    /** Seed when this [SEED_VERSION] has not been seeded yet and the record is not already present. */
    fun shouldSeed(seededVersion: Int, existingIds: Collection<String>): Boolean = seededVersion < SEED_VERSION && ID !in existingIds

    /** Subscriptions from older seed versions that should be cleaned up (only those actually present). */
    fun legacyToRemove(existingIds: Collection<String>): List<String> = LEGACY_IDS.filter { it in existingIds }

    /** True for the pre-installed list (current or legacy) — the only subscriptions [curate] touches. */
    fun isDefault(subscriptionId: String): Boolean = subscriptionId == ID || subscriptionId in LEGACY_IDS

    fun curate(profiles: List<ConnectionProfile>): List<ConnectionProfile> =
        profiles.asSequence()
            .filter { it.protocol in ALLOWED }
            .filter { it.transport !is Transport.Unsupported }
            .distinctBy { "${it.address.lowercase()}:${it.port}" }
            .sortedBy { rank(it) }
            .take(MAX_SERVERS)
            .toList()

    /** Lower = preferred. Deterministic so repeated refreshes keep a stable set (merge stays quiet). */
    internal fun rank(p: ConnectionProfile): Int {
        val reality = p.tls.reality != null
        val tls = p.tls.enabled
        val modernTransport = p.transport is Transport.WebSocket || p.transport is Transport.Grpc || p.transport is Transport.HttpUpgradeOrH2
        return when {
            reality -> 0
            tls && modernTransport -> 1
            tls -> 2
            modernTransport -> 3
            else -> 4
        }
    }
}
