package io.ucc.core.config.subscription

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain `HttpURLConnection` implementation (JVM / Android). HTTPS is enforced
 * by policy here (and by the app's network security config); `http://` is
 * refused because subscription bodies are credentials.
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
            return SubscriptionHeaders.result(
                body = body,
                subscriptionUserInfo = conn.getHeaderField("subscription-userinfo"),
                profileTitle = conn.getHeaderField("profile-title"),
                contentDisposition = conn.getHeaderField("content-disposition"),
                profileUpdateInterval = conn.getHeaderField("profile-update-interval"),
            )
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
}
