package io.ucc.app.ui.diagnostics

import io.ucc.app.testing.FakeConnectionManager
import io.ucc.app.testing.FakeProfileStore
import io.ucc.app.testing.FakeSelection
import io.ucc.applogic.InMemorySettingsStore
import io.ucc.applogic.LogBuffer
import io.ucc.app.testing.testCapabilities
import io.ucc.app.testing.testImporter
import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.UnderlyingNetwork
import io.ucc.core.engine.manager.ConnectionEvent
import io.ucc.core.model.ProfileSource
import io.ucc.core.vpn.LockdownStatus
import io.ucc.core.vpn.TunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun up() = Dispatchers.setMain(dispatcher)
    @AfterTest fun down() = Dispatchers.resetMain()

    @Test fun `snapshot and export carry state but never the profile secret`() = runTest(dispatcher) {
        val store = FakeProfileStore()
        val secret = "sup3rSecretPassw0rd"
        val profile = testImporter().import("trojan://$secret@srv.example.com:443#Berlin", ProfileSource.Manual).profiles.single()
        store.upsertAll(listOf(profile))
        val selection = FakeSelection().apply { selectedProfileId = profile.id }
        val manager = FakeConnectionManager(ConnectionState.Connected(profile.id, 1L))
        val appEvents = MutableSharedFlow<ConnectionEvent>()
        val logs = LogBuffer(backgroundScope, MutableSharedFlow(), appEvents)
        val tun = MutableStateFlow<TunState?>(TunState(42, 9000, listOf("172.19.0.1/30"), listOf("0.0.0.0/0"), emptyList(), listOf("172.19.0.2"), 0, 1, 5L))
        val net = MutableStateFlow<UnderlyingNetwork?>(UnderlyingNetwork(Any(), "wlan0", 3, false))
        val lock = MutableStateFlow<LockdownStatus?>(LockdownStatus(true, true, false, 1))
        val vm = DiagnosticsViewModel(manager, store, selection, InMemorySettingsStore(), logs, tun, net, lock, testCapabilities, "Fake 1", { true })
        vm.state.onEach { }.launchIn(backgroundScope)
        advanceUntilIdle() // let LogBuffer's collectors subscribe before emitting (SharedFlow drops otherwise)
        appEvents.emit(ConnectionEvent(1, "net", category = ConnectionEvent.Category.NETWORK))
        appEvents.emit(ConnectionEvent(2, "again", category = ConnectionEvent.Category.RECONNECT))
        appEvents.emit(ConnectionEvent(3, "Failed", error = ConnectionError.ConnectionTimeout("probe password=$secret")))
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("TROJAN srv.example.com:443 · fp=${profile.fingerprint.take(8)}", s.selectedProfile)
        assertEquals(true, s.selectedProfileSupported)
        assertEquals(1, s.networkEvents); assertEquals(1, s.reconnectEvents); assertEquals(1, s.errorEvents)
        assertEquals(42, s.tun?.fd); assertEquals("wlan0", s.network?.interfaceName)

        val text = vm.exportText()
        assertTrue("wlan0" in text && "fd=42" in text && "alwaysOn=true" in text, text)
        assertFalse(secret in text, "export leaked the credential: $text")
        assertFalse(secret in (s.lastError ?: ""), "last error leaked the credential")
    }
}
