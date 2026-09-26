package io.ucc.core.singbox.android

import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.DelayProbeSession
import io.ucc.core.engine.TunProvider
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.singbox.SingBoxConfigGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Real HTTP delay tests without connecting (see `CoreDelayProbe`).
 *
 * Implementation: a second, TUN-less libbox `CommandServer` whose gRPC listener
 * is **never started** (the tunnel's server owns `command.sock`); we only use
 * `startOrReloadService` / `closeService`. The instance carries one outbound per
 * profile and a Clash API bound to `127.0.0.1:<free port>` with a random 128-bit
 * secret; `GET /proxies/{id}/delay?url=…&timeout=…` then performs sing-box's own
 * URL test (HEAD request through the outbound) and returns the delay in ms.
 *
 * Isolation from the tunnel: no `cache_file` (the tunnel owns cache.db), a
 * separate platform interface + interface bridge, and `auto_detect_interface`
 * whose protect hook uses the tunnel's [TunProvider] when one is active (so a
 * probe never rides through the current tunnel) and is a no-op otherwise.
 *
 * Only one session runs at a time. Nothing here logs URLs of user servers,
 * credentials, or the API secret.
 */
internal class SingBoxDelayProbe(
    private val generator: SingBoxConfigGenerator,
    private val newPlatform: (TunProvider?) -> AndroidPlatformInterface,
    private val activeTunProvider: () -> TunProvider?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private companion object {
        const val TAG = "SingBoxProbe"
        const val PORT_SEARCH_START = 27_000
        val UNSUPPORTED: Set<Protocol> = setOf(Protocol.WIREGUARD) // endpoints are not addressable via /proxies
    }

    private val mutex = Mutex()
    private val random = SecureRandom()

    fun supports(profile: ConnectionProfile): Boolean = profile.protocol !in UNSUPPORTED

    suspend fun <T> open(profiles: List<ConnectionProfile>, options: CoreStartOptions, block: suspend (DelayProbeSession) -> T): T = mutex.withLock {
        require(profiles.all(::supports)) { "unsupported profile passed to delay probe" }
        val secret = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val (server, port) = withContext(ioDispatcher) {
            val port = try {
                Libbox.availablePort(PORT_SEARCH_START + random.nextInt(20_000))
            } catch (e: Exception) {
                throw CoreException(ConnectionError.CoreFailure("delay probe: no loopback port: ${e.message}"), e)
            }
            val config = generator.generateProbe(profiles, options, port, secret)
            try {
                Libbox.checkConfig(config)
            } catch (e: Exception) {
                throw CoreException(ConnectionError.InvalidConfiguration("delay probe config rejected: ${redact(e.message)}"), e)
            }
            val platform = newPlatform(activeTunProvider())
            val server = CommandServer(silentHandler, platform)
            try {
                server.startOrReloadService(config, OverrideOptions())
            } catch (e: Exception) {
                runCatching { server.close() }
                throw CoreException(ConnectionError.CoreFailure("delay probe start: ${redact(e.message)}"), e)
            }
            server to port
        }
        try {
            block(Session(port, secret))
        } finally {
            withContext(ioDispatcher + kotlinx.coroutines.NonCancellable) {
                runCatching { server.closeService() }.onFailure { Log.w(TAG, "closeService: ${it.javaClass.simpleName}") }
                runCatching { server.close() }
            }
        }
    }

    private inner class Session(private val port: Int, private val secret: String) : DelayProbeSession {
        override suspend fun measure(profileId: String, url: String, timeoutMs: Long): Long = withContext(ioDispatcher) {
            val q = "url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=" + timeoutMs.coerceIn(1_000, 60_000)
            val endpoint = URL("http://127.0.0.1:$port/proxies/" + URLEncoder.encode(profileId, "UTF-8") + "/delay?$q")
            var conn: HttpURLConnection? = null
            try {
                conn = (endpoint.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = (timeoutMs + 3_000).toInt()
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Authorization", "Bearer $secret")
                    setRequestProperty("Connection", "close")
                }
                when (val code = conn.responseCode) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        Regex("\"delay\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
                            ?: throw CoreException(ConnectionError.CoreFailure("delay probe: malformed response"))
                    }
                    504 -> throw CoreException(ConnectionError.ConnectionTimeout("delay probe exceeded ${timeoutMs}ms"))
                    503 -> throw CoreException(ConnectionError.CoreFailure("delay probe: outbound failed"))
                    404 -> throw CoreException(ConnectionError.InvalidConfiguration("delay probe: outbound not in session"))
                    401 -> throw CoreException(ConnectionError.CoreFailure("delay probe: unauthorized"))
                    else -> throw CoreException(ConnectionError.CoreFailure("delay probe: HTTP $code"))
                }
            } catch (e: CoreException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw CoreException(ConnectionError.ConnectionTimeout("delay probe timed out"), e)
            } catch (e: Exception) {
                throw CoreException(ConnectionError.CoreFailure("delay probe: ${e.javaClass.simpleName}"), e)
            } finally {
                conn?.disconnect()
            }
        }
    }

    private val silentHandler = object : CommandServerHandler {
        override fun serviceStop() = Unit
        override fun serviceReload() = Unit
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply { available = false; enabled = false }
        override fun setSystemProxyEnabled(enabled: Boolean) = Unit
        override fun writeDebugMessage(message: String?) = Unit
    }

    private fun redact(text: String?): String = io.ucc.core.engine.ErrorClassifier.redact(text.orEmpty())
}
