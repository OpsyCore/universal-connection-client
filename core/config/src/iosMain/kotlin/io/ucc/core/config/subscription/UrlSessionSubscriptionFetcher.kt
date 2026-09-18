@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.ucc.core.config.subscription

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `NSURLSession` implementation of [SubscriptionFetcher] for Apple targets.
 * Mirrors the JVM [HttpSubscriptionFetcher] contract exactly:
 *  - `https://` only unless [allowPlainHttp] (redirects to `http://` are refused as well);
 *  - `User-Agent` / `Accept: *_/_*` request headers;
 *  - declared `Content-Length` above [maxBodyBytes] → [SubscriptionFetchError.TooLarge] before the
 *    body is read; a streamed body that crosses the limit aborts the task the same way;
 *  - non-2xx → [SubscriptionFetchError.Http]; transport errors → [SubscriptionFetchError.Network];
 *  - header interpretation is delegated to the shared [SubscriptionHeaders] (no parsing duplicated here).
 * Bodies are never logged.
 */
public class UrlSessionSubscriptionFetcher(
    private val userAgent: String,
    private val maxBodyBytes: Long = 4L * 1024 * 1024,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val allowPlainHttp: Boolean = false,
) : SubscriptionFetcher {

    override suspend fun fetch(url: String): SubscriptionFetchResult {
        val nsUrl = NSURL.URLWithString(url.trim()) ?: throw SubscriptionFetchError.InvalidUrl("not a URL")
        if (!schemeAllowed(nsUrl.scheme)) throw SubscriptionFetchError.InvalidUrl("only https:// subscription URLs are accepted")
        if (nsUrl.host.isNullOrBlank()) throw SubscriptionFetchError.InvalidUrl("missing host")

        val config = NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            timeoutIntervalForRequest = readTimeoutMs / 1000.0
            timeoutIntervalForResource = (connectTimeoutMs + readTimeoutMs) / 1000.0
            HTTPShouldSetCookies = false
        }
        // `requestWithURL` is typed as the NSURLRequest superclass in K/N; use the initWithURL: constructor to keep the mutable type.
        val request = NSMutableURLRequest(uRL = nsUrl).apply {
            setValue(userAgent, forHTTPHeaderField = "User-Agent")
            setValue("*/*", forHTTPHeaderField = "Accept")
        }
        val delegate = Delegate(maxBodyBytes, ::schemeAllowed)
        val session = NSURLSession.sessionWithConfiguration(config, delegate, delegateQueue = null)
        try {
            val (response, body) = suspendCancellableCoroutine { cont ->
                delegate.continuation = { result -> if (cont.isActive) result.fold(cont::resume, cont::resumeWithException) }
                val task = session.dataTaskWithRequest(request)
                cont.invokeOnCancellation { task.cancel() }
                task.resume()
            }
            val code = response.statusCode.toInt()
            if (code !in 200..299) throw SubscriptionFetchError.Http(code)
            return SubscriptionHeaders.result(
                body = body.decodeToString(),
                subscriptionUserInfo = response.header("subscription-userinfo"),
                profileTitle = response.header("profile-title"),
                contentDisposition = response.header("content-disposition"),
                profileUpdateInterval = response.header("profile-update-interval"),
            )
        } finally {
            session.finishTasksAndInvalidate()
        }
    }

    private fun schemeAllowed(scheme: String?): Boolean {
        val s = scheme?.lowercase()
        return s == "https" || (allowPlainHttp && s == "http")
    }

    /** Header lookup is case-insensitive on `NSHTTPURLResponse`. */
    private fun NSHTTPURLResponse.header(name: String): String? = valueForHTTPHeaderField(name)

    private class Delegate(
        private val limit: Long,
        private val schemeAllowed: (String?) -> Boolean,
    ) : NSObject(), NSURLSessionDataDelegateProtocol {
        var continuation: ((Result<Pair<NSHTTPURLResponse, ByteArray>>) -> Unit)? = null
        private var response: NSHTTPURLResponse? = null
        private val chunks = ArrayList<ByteArray>()
        private var total = 0L
        private var failure: SubscriptionFetchError? = null

        private fun finish(result: Result<Pair<NSHTTPURLResponse, ByteArray>>) {
            val c = continuation ?: return
            continuation = null
            c(result)
        }

        override fun URLSession(
            session: NSURLSession,
            dataTask: NSURLSessionDataTask,
            didReceiveResponse: NSURLResponse,
            completionHandler: (NSURLSessionResponseDisposition) -> Unit,
        ) {
            val http = didReceiveResponse as? NSHTTPURLResponse
            if (http == null) {
                failure = SubscriptionFetchError.Network("non-HTTP response")
                completionHandler(NSURLSessionResponseCancel); return
            }
            response = http
            val declared = didReceiveResponse.expectedContentLength
            if (declared > limit) {
                failure = SubscriptionFetchError.TooLarge(limit)
                completionHandler(NSURLSessionResponseCancel); return
            }
            completionHandler(NSURLSessionResponseAllow)
        }

        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
            total += didReceiveData.length.toLong()
            if (total > limit) {
                failure = SubscriptionFetchError.TooLarge(limit)
                dataTask.cancel(); return
            }
            chunks += didReceiveData.toByteArray()
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) {
            // HttpURLConnection never followed https→http; keep that guarantee (the 3xx then surfaces as Http(code)).
            if (schemeAllowed(newRequest.URL?.scheme)) completionHandler(newRequest) else completionHandler(null)
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            failure?.let { finish(Result.failure(it)); return }
            if (didCompleteWithError != null) {
                finish(Result.failure(SubscriptionFetchError.Network(describe(didCompleteWithError)))); return
            }
            val resp = response ?: run { finish(Result.failure(SubscriptionFetchError.Network("no response"))); return }
            val body = ByteArray(total.toInt())
            var off = 0
            for (c in chunks) { c.copyInto(body, off); off += c.size }
            finish(Result.success(resp to body))
        }

        /** Detail string without host/URL: domain + code only (the JVM side used the exception class name). */
        private fun describe(e: NSError): String =
            if (e.domain == NSURLErrorDomain) "NSURLError(${e.code})" else "${e.domain}(${e.code})"
    }
}

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
