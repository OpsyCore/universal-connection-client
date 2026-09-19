package io.ucc.core.singbox.apple

import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.TunRequest
import io.ucc.core.engine.manager.Clock
import io.ucc.core.engine.manager.ProfileProvider
import io.ucc.core.engine.manager.StartOptionsProvider
import io.ucc.core.engine.manager.TunnelHost
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.iosvpn.TunnelConfiguration
import io.ucc.iosvpn.TunnelSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal const val SECRET = "trojan-password-DO-NOT-LEAK"

internal fun profile(id: String = "p1") = ConnectionProfile(
    id = id, name = "n", protocol = Protocol.TROJAN, address = "srv.example.com", port = 443,
    authentication = Authentication.Trojan(SECRET), tls = TlsSettings(enabled = true, serverName = "srv.example.com"),
)

internal fun startRequest(id: String = "p1") = TunnelSession.StartRequest(
    id,
    TunnelConfiguration(tunnelRemoteAddress = "127.0.0.1", mtu = 1500, inet4Addresses = listOf(TunnelConfiguration.Prefix("172.19.0.1", 30)), dnsServers = listOf("1.1.1.1")),
)

internal val testClock = object : Clock { override fun nowMs() = 42L }

/** Scripted Libbox stand-in. It records the call sequence and NEVER pretends to move packets. */
internal class FakeLibbox(available: Boolean = true) : LibboxServiceFactory {
    val calls = mutableListOf<String>()
    var failSetup = false
    var failCreate: Throwable? = null
    var failCheck: Throwable? = null
    var failStart: Throwable? = null
    var failClose: Throwable? = null
    var startGate: CompletableDeferred<Unit>? = null
    var listener: LibboxListener? = null
    var tun: TunProvider? = null
    var services = 0
    var lastConfig: String? = null

    override val available: LibboxAvailability =
        if (available) LibboxAvailability.Available("fake") else LibboxAvailability.Unavailable("test: not linked")

    override fun setup(setup: LibboxSetup) {
        calls += "setup"
        if (available is LibboxAvailability.Unavailable) throw LibboxUnavailableException("test: not linked")
        if (failSetup) throw LibboxUnavailableException("setup failed")
    }

    override fun create(listener: LibboxListener, tun: TunProvider): LibboxService {
        calls += "create"
        if (available is LibboxAvailability.Unavailable) throw LibboxUnavailableException("test: not linked")
        failCreate?.let { throw it }
        this.listener = listener; this.tun = tun; services++
        return object : LibboxService {
            override fun checkConfig(configJson: String) { calls += "check"; failCheck?.let { throw it } }
            override fun startOrReload(configJson: String) { calls += "start"; lastConfig = configJson; failStart?.let { throw it } }
            override fun closeService() { calls += "closeService"; failClose?.let { throw it } }
            override fun close() { calls += "close" }
        }
    }
}

internal class FakeProfiles(vararg profiles: ConnectionProfile) : ProfileProvider {
    private val all = profiles.toList()
    override suspend fun byId(id: String) = all.firstOrNull { it.id == id }
}

internal class FakeTunnelHost : TunnelHost {
    var acquired = 0; var released = 0
    override val revoked: Flow<Unit> = MutableSharedFlow()
    override suspend fun acquire(profile: ConnectionProfile): TunProvider {
        acquired++
        return object : TunProvider {
            override fun openTun(request: TunRequest) = 7
            override fun protectSocket(fd: Int) = true
        }
    }
    override suspend fun release() { released++ }
}

internal fun adapter(libbox: FakeLibbox) = AppleSingBoxCoreAdapter(libbox, LibboxSetup("/b", "/w", "/t", false), testClock)

internal fun engine(libbox: FakeLibbox, host: FakeTunnelHost = FakeTunnelHost(), profiles: ProfileProvider = FakeProfiles(profile())) =
    AppleSingBoxTunnelEngine(adapter(libbox), profiles, StartOptionsProvider.Defaults, host)
