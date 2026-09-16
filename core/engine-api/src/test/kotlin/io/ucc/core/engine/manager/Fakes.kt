package io.ucc.core.engine.manager

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreDescriptor
import io.ucc.core.engine.CoreEvent
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreLogLine
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.TunRequest
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow

internal fun profile(id: String = "p1") = ConnectionProfile(
    id = id,
    name = "test",
    protocol = Protocol.TROJAN,
    address = "example.org",
    port = 443,
    authentication = Authentication.Trojan("pw"),
)

internal class FakeClock : Clock {
    var now = 1_000L
    override fun nowMs(): Long = now
}

internal class FakeTun : TunProvider {
    override fun openTun(request: TunRequest): Int = 42
    override fun protectSocket(fd: Int): Boolean = true
}

internal class FakeTunnelHost : TunnelHost {
    var acquireCount = 0
    var releaseCount = 0
    var failWith: ConnectionError? = null
    override val revoked = MutableSharedFlow<Unit>()

    override suspend fun acquire(profile: ConnectionProfile): TunProvider {
        acquireCount++
        failWith?.let { throw CoreException(it) }
        return FakeTun()
    }

    override suspend fun release() {
        releaseCount++
    }
}

internal class FakeCore : CoreAdapter {
    var startCount = 0
    var stopCount = 0
    var networkChangedCount = 0
    var startError: ConnectionError? = null
    /** Each call to urlTest pops the head; null = success with 50ms, else throws. */
    val probeScript = ArrayDeque<ConnectionError?>()
    var running = false
    var lastOptions: CoreStartOptions? = null

    override val descriptor = CoreDescriptor("fake", "Fake", "0")
    override val capabilities = CoreCapabilities(
        protocols = Protocol.entries.toSet(), transports = setOf("tcp"), reality = true,
        utlsFingerprints = true, perAppRouting = true, ruleSets = true, fakeIp = true, hotReload = true,
    )
    override val statistics = MutableSharedFlow<CoreStatistics>()
    override val logs = MutableSharedFlow<CoreLogLine>()
    override val events = MutableSharedFlow<CoreEvent>()

    override suspend fun start(profile: ConnectionProfile, options: CoreStartOptions, tun: TunProvider) {
        startCount++
        lastOptions = options
        startError?.let { throw CoreException(it) }
        running = true
    }

    override suspend fun reload(profile: ConnectionProfile, options: CoreStartOptions) = Unit
    override suspend fun stop() { stopCount++; running = false }
    override suspend fun onNetworkChanged() { networkChangedCount++ }
    override suspend fun onPause() = Unit
    override suspend fun onResume() = Unit

    override suspend fun urlTest(url: String, timeoutMs: Long): Long {
        if (!running) throw CoreException(ConnectionError.CoreFailure("not running"))
        val next = if (probeScript.isEmpty()) null else probeScript.removeFirst()
        if (next != null) throw CoreException(next)
        return 50
    }
}

internal class FakeNetwork : NetworkMonitor {
    override val events = MutableSharedFlow<NetworkEvent>()
}

internal class FakeProfiles(private val map: Map<String, ConnectionProfile>) : ProfileProvider {
    override suspend fun byId(id: String): ConnectionProfile? = map[id]
}

internal suspend fun <T> StateFlow<T>.awaitValue(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
    withTimeout(timeoutMs) { first(predicate) }

internal val fastPolicy = ReconnectPolicy(
    probeTimeoutMs = 500,
    initialProbeAttempts = 2,
    maxReconnectAttempts = 3,
    backoffBaseMs = 1,
    maxBackoffMs = 4,
)
