package io.ucc.core.singbox.apple

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreDescriptor
import io.ucc.core.engine.CoreEvent
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreLogLine
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.ErrorClassifier
import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.manager.Clock
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.singbox.SingBoxCapabilities
import io.ucc.core.singbox.SingBoxConfigGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Apple twin of the Android `SingBoxCoreAdapter`, written against [LibboxService]
 * instead of the Libbox classes so it is JVM-testable and identical in sequencing:
 *
 *   start():  setup (once) → generate JSON (singbox-config) → create service (once)
 *             → checkConfig → startOrReload → running
 *   stop():   closeService (service object kept for the next start)
 *   shutdown(): stop + close (provider termination)
 *
 * Errors are classified with the shared [ErrorClassifier]; configuration JSON is never
 * placed in an exception, event or log.
 */
public class AppleSingBoxCoreAdapter(
    private val libbox: LibboxServiceFactory,
    private val setup: LibboxSetup,
    private val clock: Clock,
    private val generator: SingBoxConfigGenerator = SingBoxConfigGenerator(),
) : CoreAdapter {
    private val mutex = Mutex()
    private var service: LibboxService? = null
    private var setupDone = false
    private var serviceRunning = false
    private var closed = false

    override val descriptor: CoreDescriptor
        get() = CoreDescriptor(
            id = AppleSingBoxCoreFactory.ID,
            displayName = "sing-box",
            version = (libbox.available as? LibboxAvailability.Available)?.version ?: "unavailable",
        )
    override val capabilities: CoreCapabilities = SingBoxCapabilities.capabilities

    private val _statistics = MutableSharedFlow<CoreStatistics>(replay = 1, extraBufferCapacity = 8)
    private val _logs = MutableSharedFlow<CoreLogLine>(extraBufferCapacity = 256)
    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 16)
    override val statistics: Flow<CoreStatistics> = _statistics
    override val logs: Flow<CoreLogLine> = _logs
    override val events: Flow<CoreEvent> = _events

    /** Last statistics sample, for the extension's IPC statistics reply. */
    public val lastStatistics: CoreStatistics? get() = _statistics.replayCache.lastOrNull()
    public val isRunning: Boolean get() = serviceRunning

    private val listener = object : LibboxListener {
        override fun onServiceStopped() {
            serviceRunning = false
            _events.tryEmit(CoreEvent.Fatal(ConnectionError.CoreFailure("core requested service stop")))
        }
        override fun onStatus(status: LibboxStatus) {
            _statistics.tryEmit(
                CoreStatistics(
                    status.uplinkBytesPerSecond, status.downlinkBytesPerSecond, status.uplinkTotalBytes, status.downlinkTotalBytes,
                    status.connectionsIn, status.connectionsOut, status.memoryBytes, status.goroutines,
                ),
            )
        }
        override fun onLog(level: Int, message: String) { _logs.tryEmit(CoreLogLine(level, ErrorClassifier.redact(message), clock.nowMs())) }
    }

    override suspend fun start(profile: ConnectionProfile, options: CoreStartOptions, tun: TunProvider) {
        val config = generator.generate(profile, options) // CoreException(Unsupported/Invalid)
        mutex.withLock {
            if (closed) throw CoreException(ConnectionError.CoreFailure("adapter already shut down"))
            if (!setupDone) {
                try { libbox.setup(setup) } catch (e: LibboxUnavailableException) { throw CoreException(ConnectionError.CoreFailure(e.message ?: "Libbox unavailable"), e) }
                setupDone = true
            }
            val svc = service ?: try { libbox.create(listener, tun) } catch (e: LibboxUnavailableException) {
                throw CoreException(ConnectionError.CoreFailure(e.message ?: "Libbox unavailable"), e)
            } catch (e: Exception) {
                throw CoreException(ConnectionError.CoreFailure("command server start: ${e::class.simpleName}"), e)
            }.also { service = it }
            try { svc.checkConfig(config) } catch (e: Exception) {
                throw CoreException(ConnectionError.InvalidConfiguration("sing-box rejected configuration: ${ErrorClassifier.redact(e.message.orEmpty())}"), e)
            }
            try { svc.startOrReload(config) } catch (e: Exception) { throw CoreException(ErrorClassifier.classifyStart(e.message, "sing-box"), e) }
            serviceRunning = true
        }
    }

    override suspend fun reload(profile: ConnectionProfile, options: CoreStartOptions) {
        val config = generator.generate(profile, options)
        mutex.withLock {
            val svc = service?.takeIf { serviceRunning } ?: throw CoreException(ConnectionError.CoreFailure("reload without a running core"))
            try { svc.startOrReload(config) } catch (e: Exception) { throw CoreException(ErrorClassifier.classifyStart(e.message, "sing-box"), e) }
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            val svc = service
            if (svc != null && serviceRunning) {
                serviceRunning = false
                try { svc.closeService() } catch (e: Exception) { throw CoreException(ConnectionError.CoreFailure("closeService: ${e::class.simpleName}"), e) }
            }
            serviceRunning = false
        }
    }

    /** Provider termination: releases the service entirely. Idempotent. */
    public suspend fun shutdown() {
        val stopFailure = runCatching { stop() }.exceptionOrNull()
        mutex.withLock {
            runCatching { service?.close() }
            service = null
            closed = true
        }
        stopFailure?.let { throw it }
    }

    override suspend fun onNetworkChanged() { /* Libbox resetNetwork — wired with the real framework */ }
    override suspend fun onPause() {}
    override suspend fun onResume() {}

    override suspend fun urlTest(url: String, timeoutMs: Long): Long =
        throw CoreException(ConnectionError.CoreFailure("urlTest requires the linked Libbox"))
}
