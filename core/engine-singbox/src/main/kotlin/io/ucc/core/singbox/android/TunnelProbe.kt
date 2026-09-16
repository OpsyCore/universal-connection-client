package io.ucc.core.singbox.android

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * End-to-end tunnel probe: a real HTTP request issued by this app through the
 * system default network — which, while our VpnService is established, *is*
 * the TUN. So the request traverses TUN → sing-box → proxy → Internet exactly
 * like user traffic. Returns wall-clock RTT in ms.
 */
internal object TunnelProbe {
    suspend fun measure(url: String, timeoutMs: Long): Long = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
            }
            val code = conn.responseCode
            if (code !in 200..399) {
                throw CoreException(ConnectionError.ConnectionTimeout("probe $url returned HTTP $code"))
            }
            (System.nanoTime() - started) / 1_000_000
        } catch (e: CoreException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CoreException(ConnectionError.ConnectionTimeout("probe timed out after ${timeoutMs}ms"), e)
        } catch (e: UnknownHostException) {
            throw CoreException(ConnectionError.DnsFailure("probe host resolution failed"), e)
        } catch (e: SSLException) {
            throw CoreException(ConnectionError.TlsFailure("probe TLS failure: ${e.message}"), e)
        } catch (e: Exception) {
            throw CoreException(ConnectionError.ConnectionTimeout("probe failed: ${e.javaClass.simpleName}: ${e.message}"), e)
        } finally {
            conn?.disconnect()
        }
    }
}
