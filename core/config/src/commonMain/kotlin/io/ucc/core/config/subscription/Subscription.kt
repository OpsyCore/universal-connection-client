package io.ucc.core.config.subscription

import io.ucc.core.platform.sha256
import io.ucc.core.platform.toHexLower

/** Stored record of a subscription source. Merging is done by [SubscriptionMerger]. */
public data class Subscription(
    val id: String,
    val url: String,
    val name: String,
    val addedAtEpochMs: Long,
    val lastFetchedAtEpochMs: Long? = null,
    val lastInfo: SubscriptionInfo? = null,
    /** User toggle; scheduled refresh skips subscriptions with this off. */
    val autoUpdate: Boolean = true,
    /** Server-suggested `profile-update-interval` (hours); null = app default. */
    val updateIntervalHours: Int? = null,
    /** Last refresh failure, already redacted (never contains the URL). */
    val lastError: String? = null,
) {
    public companion object {
        /** Stable id derived from the normalized URL so re-adding the same URL maps to the same subscription. */
        public fun idFor(url: String): String {
            val normalized = url.trim().lowercase().removeSuffix("/")
            return sha256(normalized.encodeToByteArray()).copyOfRange(0, 12).toHexLower()
        }
    }
}

/** Parsed `subscription-userinfo` header (`upload=…; download=…; total=…; expire=…`). All values optional. */
public data class SubscriptionInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireEpochSeconds: Long? = null,
) {
    public val usedBytes: Long? get() = if (uploadBytes == null && downloadBytes == null) null else (uploadBytes ?: 0) + (downloadBytes ?: 0)

    public fun isExpired(nowEpochMs: Long): Boolean = expireEpochSeconds != null && expireEpochSeconds * 1000 < nowEpochMs

    public fun isQuotaExhausted(): Boolean {
        val used = usedBytes ?: return false
        val total = totalBytes ?: return false
        return total > 0 && used >= total
    }

    public companion object {
        public fun parse(header: String?): SubscriptionInfo? {
            if (header.isNullOrBlank()) return null
            val map = header.split(';').mapNotNull { part ->
                val kv = part.trim().split('=', limit = 2)
                if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim().toLongOrNull() else null
            }.toMap()
            if (map.isEmpty()) return null
            return SubscriptionInfo(map["upload"], map["download"], map["total"], map["expire"])
        }
    }
}
