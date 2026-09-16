package io.ucc.core.config.subscription

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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

public interface SubscriptionFetcher {
    /** Throws [SubscriptionFetchError]. Never throws anything else. */
    public suspend fun fetch(url: String): SubscriptionFetchResult
}

/**
 * Plain `HttpURLConnection` implementation. HTTPS is enforced by policy here
 * (and by the app's network security config); `http://` is refused because
 * subscription bodies are credentials.
 */
public class HttpSubscriptionFetcher(
    private val userAgent: String,
    private val maxBodyBytes: Long = 4L * 1024 * 1024,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val allowPlainHttp: Boolean = false,
) : SubscriptionFetcher {

    override suspend fun fetch(url: String): SubscriptionFetchResult {
        val parsed = try { URL(url.trim()) } catch (e: Exception) { throw SubscriptionFetchError.InvalidUrl("not a URL") }
        if (parsed.protocol != "https" && !(allowPlainHttp && parsed.protocol == "http")) {
            throw SubscriptionFetchError.InvalidUrl("only https:// subscription URLs are accepted")
        }
        if (parsed.host.isNullOrBlank()) throw SubscriptionFetchError.InvalidUrl("missing host")
        val conn = try {
            (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
            }
        } catch (e: IOException) {
            throw SubscriptionFetchError.Network(e.javaClass.simpleName)
        }
        try {
            val code = try { conn.responseCode } catch (e: IOException) { throw SubscriptionFetchError.Network(e.javaClass.simpleName) }
            if (code !in 200..299) throw SubscriptionFetchError.Http(code)
            val declared = conn.contentLengthLong
            if (declared > maxBodyBytes) throw SubscriptionFetchError.TooLarge(maxBodyBytes)
            val body = conn.inputStream.use { readBounded(it, maxBodyBytes) }
            val info = SubscriptionInfo.parse(conn.getHeaderField("subscription-userinfo"))
            val title = conn.getHeaderField("profile-title")?.let(::decodeTitle)
                ?: conn.getHeaderField("content-disposition")?.let(::fileNameFromDisposition)
            val interval = conn.getHeaderField("profile-update-interval")?.trim()?.toIntOrNull()
            return SubscriptionFetchResult(body, info, title, interval)
        } catch (e: SubscriptionFetchError) {
            throw e
        } catch (e: IOException) {
            throw SubscriptionFetchError.Network(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw SubscriptionFetchError.TooLarge(limit)
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun decodeTitle(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("base64:", ignoreCase = true)) {
            return runCatching { String(java.util.Base64.getDecoder().decode(t.substring(7).trim()), Charsets.UTF_8) }.getOrNull()?.trim()?.ifEmpty { null }
        }
        return t.ifEmpty { null }
    }

    private fun fileNameFromDisposition(raw: String): String? {
        val star = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        val name = (star ?: plain)?.trim() ?: return null
        return runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name).ifEmpty { null }
    }
}
