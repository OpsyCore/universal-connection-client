package io.ucc.core.singbox.android

import android.content.Context
import android.net.Network
import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ErrorClassifier
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreDelayProbe
import io.ucc.core.engine.DelayProbeSession
import io.ucc.core.engine.InterfaceObserver
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreDescriptor
import io.ucc.core.engine.CoreEvent
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreLogLine
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.TunProvider
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.singbox.SingBoxCapabilities
import io.ucc.core.singbox.SingBoxConfigGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [CoreAdapter] backed by sing-box `libbox` (in-process). Lifecycle:
 *
 *  start(): generate JSON → CommandServer.start() (once) → startOrReloadService(json)
 *           → CommandClient(Status+Log).connect()
 *  stop():  CommandClient.disconnect() → CommandServer.closeService()
 *
 * The `CommandServer` (gRPC over a unix socket inside the app sandbox) stays
 * alive across profiles; only the inner service is (re)started. All libbox
 * calls run on [ioDispatcher] because they block.
 */
public class SingBoxCoreAdapter(
    context: Context,
    private val debug: Boolean,
    private val defaultNetwork: () -> Network?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    generator: SingBoxConfigGenerator? = null,
) : CoreAdapter, InterfaceObserver, CoreDelayProbe {

    private companion object {
        const val TAG = "SingBoxCore"
        const val STATUS_INTERVAL_NS = 1_000_000_000L
    }

    private val appContext = context.applicationContext
    private val interfaceBridge = DefaultInterfaceBridge()
    private val platform = AndroidPlatformInterface(appContext, defaultNetwork, interfaceBridge)

    /** Rule-sets live in private storage (installed from assets on first use); the generator only needs the path. */
    private val generator: SingBoxConfigGenerator = generator ?: SingBoxConfigGenerator(ruleSetDirectory = RuleSetAssets.directory(appContext).path)

    /** Delay-probe instances get their own bridge (fed by the same updates) so they never steal the tunnel's listener. */
    private val probeBridge = DefaultInterfaceBridge()
    private val delayProbe = SingBoxDelayProbe(
        generator = this.generator,
        newPlatform = { tun -> AndroidPlatformInterface(appContext, defaultNetwork, probeBridge, protectOptional = true).also { it.tunProvider = tun } },
        activeTunProvider = { platform.tunProvider },
        ioDispatcher = ioDispatcher,
    )

    private val mutex = Mutex()
    private var server: CommandServer? = null
    private var client: CommandClient? = null
    @Volatile private var serviceRunning = false

    private val _statistics = MutableSharedFlow<CoreStatistics>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _logs = MutableSharedFlow<CoreLogLine>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val statistics: Flow<CoreStatistics> = _statistics.asSharedFlow()
    override val logs: Flow<CoreLogLine> = _logs.asSharedFlow()
    override val events: Flow<CoreEvent> = _events.asSharedFlow()

    override val descriptor: CoreDescriptor
        get() = CoreDescriptor(id = "singbox", displayName = "sing-box", version = LibboxRuntime.version)

    override val capabilities: CoreCapabilities = SingBoxCapabilities.capabilities

    /** Called by the VPN layer's network monitor; forwarded to the core's interface monitor. */
    override fun onDefaultInterface(name: String, index: Int, expensive: Boolean) {
        interfaceBridge.update(name, index, expensive)
        probeBridge.update(name, index, expensive)
    }

    override fun onDefaultInterfaceLost() {
        interfaceBridge.lost()
        probeBridge.lost()
    }

    // ------------------------------------------------------------------ delay probe (CoreDelayProbe)

    override fun supports(profile: ConnectionProfile): Boolean = delayProbe.supports(profile)

    override suspend fun <T> open(profiles: List<ConnectionProfile>, options: CoreStartOptions, block: suspend (DelayProbeSession) -> T): T {
        LibboxRuntime.ensureInitialised(appContext, debug)
        return delayProbe.open(profiles, options, block)
    }

    public fun setNotificationSink(sink: ((title: String, body: String) -> Unit)?) {
        platform.notificationSink = sink?.let { s -> { n -> s(n.title, n.body) } }
    }

    // ------------------------------------------------------------------ start/stop

    override suspend fun start(profile: ConnectionProfile, options: CoreStartOptions, tun: TunProvider) {
        LibboxRuntime.ensureInitialised(appContext, debug)
        if (options.directIran || options.blockAds) withContext(ioDispatcher) { RuleSetAssets.install(appContext) }
        val config = generator.generate(profile, options) // throws CoreException(Unsupported/Invalid)
        withContext(ioDispatcher) {
            mutex.withLock {
                platform.tunProvider = tun
                val srv = server ?: CommandServer(serverHandler, platform).also {
                    try {
                        it.start()
                    } catch (e: Exception) {
                        throw CoreException(ConnectionError.CoreFailure("command server start: ${e.message}"), e)
                    }
                    server = it
                }
                try {
                    Libbox.checkConfig(config)
                } catch (e: Exception) {
                    throw CoreException(ConnectionError.InvalidConfiguration("sing-box rejected configuration: ${redact(e.message)}"), e)
                }
                try {
                    srv.startOrReloadService(config, OverrideOptions())
                } catch (e: Exception) {
                    throw CoreException(classifyStartFailure(e), e)
                }
                serviceRunning = true
                connectClientLocked()
            }
        }
    }

    override suspend fun reload(profile: ConnectionProfile, options: CoreStartOptions) {
        if (options.directIran || options.blockAds) withContext(ioDispatcher) { RuleSetAssets.install(appContext) }
        val config = generator.generate(profile, options)
        withContext(ioDispatcher) {
            mutex.withLock {
                val srv = server ?: throw CoreException(ConnectionError.CoreFailure("reload without a running core"))
                try {
                    srv.startOrReloadService(config, OverrideOptions())
                } catch (e: Exception) {
                    throw CoreException(classifyStartFailure(e), e)
                }
            }
        }
    }

    override suspend fun stop() {
        withContext(ioDispatcher) {
            mutex.withLock {
                disconnectClientLocked()
                val srv = server
                if (srv != null && serviceRunning) {
                    runCatching { srv.closeService() }.onFailure { Log.w(TAG, "closeService: ${it.message}") }
                }
                serviceRunning = false
                platform.tunProvider = null
            }
        }
    }

    /** Fully tears down the command server (process exit / service destroy). */
    public suspend fun shutdown() {
        stop()
        withContext(ioDispatcher) {
            mutex.withLock {
                runCatching { server?.close() }
                server = null
            }
        }
    }

    override suspend fun onNetworkChanged() {
        withContext(ioDispatcher) { mutex.withLock { runCatching { server?.resetNetwork() } } }
    }

    override suspend fun onPause() {
        withContext(ioDispatcher) { mutex.withLock { runCatching { server?.pause() } } }
    }

    override suspend fun onResume() {
        withContext(ioDispatcher) { mutex.withLock { runCatching { server?.wake() } } }
    }

    override suspend fun urlTest(url: String, timeoutMs: Long): Long {
        if (!serviceRunning) throw CoreException(ConnectionError.CoreFailure("core not running"))
        return TunnelProbe.measure(url, timeoutMs)
    }

    // ------------------------------------------------------------------ command client

    private fun connectClientLocked() {
        disconnectClientLocked()
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandLog)
            statusInterval = STATUS_INTERVAL_NS
        }
        val c = CommandClient(clientHandler, options)
        try {
            c.connect()
            client = c
        } catch (e: Exception) {
            // Statistics are optional for the tunnel to work; report but do not fail start.
            Log.w(TAG, "command client connect failed: ${e.message}")
            _events.tryEmit(CoreEvent.Info("statistics unavailable: ${e.message}"))
        }
    }

    private fun disconnectClientLocked() {
        client?.let { runCatching { it.disconnect() } }
        client = null
    }

    private val clientHandler = object : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String?) {
            _events.tryEmit(CoreEvent.Info("command client disconnected: ${message ?: ""}"))
        }
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: LogIterator) {
            val now = System.currentTimeMillis()
            while (messageList.hasNext()) {
                val e = messageList.next()
                _logs.tryEmit(CoreLogLine(e.level, redact(e.message) ?: "", now))
            }
        }
        override fun writeStatus(message: StatusMessage) {
            _statistics.tryEmit(
                CoreStatistics(
                    uplinkBytesPerSecond = message.uplink,
                    downlinkBytesPerSecond = message.downlink,
                    uplinkTotalBytes = message.uplinkTotal,
                    downlinkTotalBytes = message.downlinkTotal,
                    connectionsIn = message.connectionsIn,
                    connectionsOut = message.connectionsOut,
                    memoryBytes = message.memory,
                    goroutines = message.goroutines,
                ),
            )
        }
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    }

    private val serverHandler = object : CommandServerHandler {
        override fun serviceStop() {
            // Core asked to stop (e.g. via clash API) — surface as a retryable fatal so the manager decides.
            serviceRunning = false
            _events.tryEmit(CoreEvent.Fatal(ConnectionError.CoreFailure("core requested service stop")))
        }
        override fun serviceReload() = Unit
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply { available = false; enabled = false }
        override fun setSystemProxyEnabled(enabled: Boolean) = Unit
        override fun writeDebugMessage(message: String?) {
            if (debug) Log.d(TAG, message ?: "")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun classifyStartFailure(e: Exception): ConnectionError = ErrorClassifier.classifyStart(e.message, "sing-box")

    /** Best-effort scrubbing of credential-looking tokens from core messages before they reach logs. */
    private fun redact(text: String?): String? {
        if (text == null) return null
        return text
            .replace(Regex("(?i)(password|uuid|private_key|pre_shared_key|auth_str|psk)(\"?\\s*[:=]\\s*\"?)[^\\s\",}]+"), "$1$2***")
            .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<uuid>")
    }
}
