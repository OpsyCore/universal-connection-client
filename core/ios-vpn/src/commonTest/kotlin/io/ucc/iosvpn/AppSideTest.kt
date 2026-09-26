package io.ucc.iosvpn

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.manager.Clock
import io.ucc.core.engine.manager.ConnectionEvent
import io.ucc.core.engine.manager.ConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class RecordingManager : ConnectionManager {
    val calls = mutableListOf<String>()
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
    override val transitions: Flow<ConnectionState> = emptyFlow()
    override val statistics: StateFlow<CoreStatistics?> = MutableStateFlow(null)
    override val events: Flow<ConnectionEvent> = emptyFlow()
    override fun connect(profileId: String, options: CoreStartOptions?) { calls += "connect" }
    override fun disconnect() { calls += "disconnect" }
    override fun attachRunningTunnel(profileId: String, sinceEpochMs: Long) { calls += "attach:$profileId" }
}

class VpnStatusMapperTest {
    @Test fun maps_every_status() {
        assertEquals(ConnectionState.Disconnected, VpnStatusMapper.toConnectionState(VpnStatus.INVALID, "p", 1))
        assertEquals(ConnectionState.Disconnected, VpnStatusMapper.toConnectionState(VpnStatus.DISCONNECTED, "p", 1))
        assertEquals(ConnectionState.Connecting("p"), VpnStatusMapper.toConnectionState(VpnStatus.CONNECTING, "p", 1))
        assertEquals(ConnectionState.Connected("p", 1), VpnStatusMapper.toConnectionState(VpnStatus.CONNECTED, "p", 1))
        assertIs<ConnectionState.Reconnecting>(VpnStatusMapper.toConnectionState(VpnStatus.REASSERTING, "p", 1))
        assertEquals(ConnectionState.Stopping("p"), VpnStatusMapper.toConnectionState(VpnStatus.DISCONNECTING, "p", 1))
        assertEquals(ConnectionState.Disconnected, VpnStatusMapper.toConnectionState(VpnStatus.CONNECTED, null, 1))
    }

    @Test fun mirror_attaches_once_and_disconnects_once() {
        val m = RecordingManager(); var pid: String? = "p1"
        val mirror = VpnStatusMirror(m, object : Clock { override fun nowMs() = 5L }) { pid }
        mirror.onStatus(VpnStatus.CONNECTING); mirror.onStatus(VpnStatus.CONNECTED); mirror.onStatus(VpnStatus.CONNECTED); mirror.onStatus(VpnStatus.REASSERTING)
        mirror.onStatus(VpnStatus.DISCONNECTED); mirror.onStatus(VpnStatus.DISCONNECTED)
        assertEquals(listOf("attach:p1", "disconnect"), m.calls)
        pid = null; mirror.onStatus(VpnStatus.CONNECTED); assertEquals(2, m.calls.size)
    }
}

/** In-memory [VpnController] proving the abstraction is usable by shared code without Apple APIs. */
class FakeVpnController : VpnController {
    override val status = MutableStateFlow(VpnStatus.INVALID)
    var installed: Pair<String, TunnelConfiguration>? = null
    override suspend fun install(profileId: String, tunnel: TunnelConfiguration, localizedDescription: String) { installed = profileId to tunnel.validated(); status.value = VpnStatus.DISCONNECTED }
    override suspend fun uninstall() { installed = null; status.value = VpnStatus.INVALID }
    override suspend fun start(profileId: String) { if (installed == null) throw VpnError.PermissionOrConfigurationUnavailable("no saved VPN configuration"); status.value = VpnStatus.CONNECTING }
    override suspend fun stop() { status.value = VpnStatus.DISCONNECTED }
    override val ipc: IpcClient? get() = null
}

class VpnControllerContractTest {
    @Test fun start_without_configuration_is_typed() = runTest {
        val c = FakeVpnController()
        assertFailsWith<VpnError.PermissionOrConfigurationUnavailable> { c.start("p") }
        c.install("p", sampleConfig(), "UCC")
        c.start("p"); assertEquals(VpnStatus.CONNECTING, c.status.value)
        c.stop(); c.uninstall(); assertEquals(VpnStatus.INVALID, c.status.value)
        assertFailsWith<VpnError.InvalidTunnelConfiguration> { c.install("p", sampleConfig().copy(mtu = 1), "UCC") }
    }

    @Test fun target_ids_are_consistent() {
        val ids = AppleTargetIds.Default
        assertEquals("group.io.ucc.ios", ids.appGroup); assertEquals("io.ucc.ios.tunnel", ids.tunnelBundleId)
        assertEquals(true, ids.keychainAccessGroup.endsWith(ids.appGroup))
    }
}
