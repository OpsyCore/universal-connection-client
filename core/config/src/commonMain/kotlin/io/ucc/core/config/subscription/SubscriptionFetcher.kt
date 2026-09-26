package io.ucc.core.config.subscription

import io.ucc.core.platform.Base64Codec
import io.ucc.core.platform.UrlCodec

/** Result of downloading a subscription body. The body is raw text; the importer decides what it is. */
public data class SubscriptionFetchResult(
    val body: String,
    val info: SubscriptionInfo?,
    /** `profile-title` / `content-disposition` derived name, if the server sent one. */
    val suggestedName: String?,
    /** `profile-update-interval` in hours, if sent. */
    val updateIntervalHours: Int?,
)

public sealed class SubscriptionFetchError(message: String) : Exception(message) {
    public class InvalidUrl(detail: String) : SubscriptionFetchError(detail)
    public class Http(public val code: Int) : SubscriptionFetchError("HTTP $code")
    public class TooLarge(public val limit: Long) : SubscriptionFetchError("body exceeds $limit bytes")
    public class Network(detail: String) : SubscriptionFetchError(detail)
}

/**
 * Platform boundary for subscription downloads. The JVM/Android implementation is
 * [HttpSubscriptionFetcher] (jvmMain); other platforms supply their own HTTP client
 * and build the result with [SubscriptionHeaders].
 */
public interface SubscriptionFetcher {
    /** Throws [SubscriptionFetchError]. Never throws anything else. */
    public suspend fun fetch(url: String): SubscriptionFetchResult
}

/**
 * Interpretation of the subscription-related response headers, shared by every
 * platform fetcher so the derived name / interval / usage are identical everywhere.
 */
public object SubscriptionHeaders {
    /** Builds the result from raw header values (`null` when the server did not send one). */
    public fun result(
        body: String,
        subscriptionUserInfo: String?,
        profileTitle: String?,
        contentDisposition: String?,
        profileUpdateInterval: String?,
    ): SubscriptionFetchResult {
        val info = SubscriptionInfo.parse(subscriptionUserInfo)
        val title = profileTitle?.let(::decodeTitle) ?: contentDisposition?.let(::fileNameFromDisposition)
        val interval = profileUpdateInterval?.trim()?.toIntOrNull()
        return SubscriptionFetchResult(body, info, title, interval)
    }

    /** `profile-title`: plain text or `base64:<...>`. */
    public fun decodeTitle(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("base64:", ignoreCase = true)) {
            return runCatching { Base64Codec.decodeStrict(t.substring(7).trim()).decodeToString() }.getOrNull()?.trim()?.ifEmpty { null }
        }
        return t.ifEmpty { null }
    }

    /** `content-disposition`: `filename*=UTF-8''…` preferred over `filename=…`. */
    public fun fileNameFromDisposition(raw: String): String? {
        val star = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val name = (star ?: plain)?.trim() ?: return null
        // Form-decoding semantics as before (URLDecoder): '+' means space here.
        return runCatching { UrlCodec.decode(name.replace("+", "%20")) }.getOrDefault(name).ifEmpty { null }
    }
}
