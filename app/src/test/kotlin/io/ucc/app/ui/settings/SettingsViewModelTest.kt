package io.ucc.app.ui.settings

import io.ucc.app.data.ConnectionSettings
import io.ucc.app.data.FakeConnectionManager
import io.ucc.app.data.InMemorySettingsStore
import io.ucc.app.data.LogBuffer
import io.ucc.app.data.Notices
import io.ucc.app.data.ThemeMode
import io.ucc.app.data.ThemeStore
import io.ucc.app.data.testCapabilities
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreDescriptor
import io.ucc.core.engine.CoreFactory
import io.ucc.core.engine.CorePlatform
import io.ucc.core.engine.RouteAction
import io.ucc.core.engine.ThirdPartyNotice
import io.ucc.core.vpn.LockdownStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun up() = Dispatchers.setMain(dispatcher)
    @AfterTest fun down() = Dispatchers.resetMain()

    private class FakeTheme : ThemeStore {
        override val themeFlow = MutableStateFlow(ThemeMode.SYSTEM)
        override var theme: ThemeMode get() = themeFlow.value; set(v) { themeFlow.value = v }
    }
    private object NoCore : CoreFactory {
        override val id = "fake"; override val descriptor = CoreDescriptor("fake", "Fake", "0")
        override val capabilities: CoreCapabilities = testCapabilities
        override val notices = listOf(ThirdPartyNotice("x", "1", "MIT", "https://x"))
        override fun create(platform: CorePlatform): CoreAdapter = error("not used")
    }

    private val store = InMemorySettingsStore()
    private val lockdown = MutableStateFlow<LockdownStatus?>(null)

    private fun kotlinx.coroutines.test.TestScope.vm(caps: CoreCapabilities = testCapabilities): SettingsViewModel {
        val buf = LogBuffer(backgroundScope, MutableSharedFlow(), MutableSharedFlow())
        val vm = SettingsViewModel(store, FakeConnectionManager(), caps, "Fake 0", FakeTheme(), buf, Notices(NoCore), lockdown, "1.0")
        vm.state.onEach { }.launchIn(backgroundScope)
        return vm
    }

    @Test fun `kill switch state is whatever Android reported, never assumed`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        assertNull(vm.state.value.lockdown, "before the service ran we must not claim any kill-switch state")
        lockdown.value = LockdownStatus(supported = true, alwaysOn = true, lockdown = false, observedAtEpochMs = 1); advanceUntilIdle()
        assertEquals(false, vm.state.value.lockdown?.lockdown)
        lockdown.value = LockdownStatus(supported = true, alwaysOn = true, lockdown = true, observedAtEpochMs = 2); advanceUntilIdle()
        assertEquals(true, vm.state.value.lockdown?.lockdown)
    }

    @Test fun `rules can be added edited reordered toggled and removed`() = runTest(dispatcher) {
        val vm = vm(); advanceUntilIdle()
        vm.addRule(RouteAction.DIRECT, "a.com, *.ir\n10.0.0.0/8")
        vm.addRule(RouteAction.BLOCK, "ads.net")
        vm.addRule(RouteAction.PROXY, "   ") // ignored
        advanceUntilIdle()
        val s1 = vm.state.value.settings
        assertEquals(2, s1.rules.size)
        assertEquals(listOf("a.com", "*.ir", "10.0.0.0/8"), s1.rules[0].items)
        val blockId = s1.rules[1].id
        vm.moveRule(blockId, up = true); advanceUntilIdle()
        assertEquals(blockId, vm.state.value.settings.rules[0].id)
        vm.moveRule(blockId, up = true); advanceUntilIdle() // no-op at top
        assertEquals(blockId, vm.state.value.settings.rules[0].id)
        vm.toggleRule(blockId); vm.editRule(blockId, RouteAction.DIRECT, "x.org"); advanceUntilIdle()
        val edited = vm.state.value.settings.rules[0]
        assertEquals(false, edited.enabled); assertEquals(RouteAction.DIRECT, edited.action); assertEquals(listOf("x.org"), edited.items)
        vm.removeRule(blockId); advanceUntilIdle()
        assertEquals(1, vm.state.value.settings.rules.size)
    }

    @Test fun `problems are surfaced and per-app is gated on capabilities`() = runTest(dispatcher) {
        val vm = vm(testCapabilities.copy(perAppRouting = false)); advanceUntilIdle()
        vm.setRemoteDns("192.168.0.1"); vm.setPerAppMode(ConnectionSettings.PerAppMode.INCLUDE); vm.togglePackage("com.x"); advanceUntilIdle()
        assertTrue(ConnectionSettings.Problem.RemoteDnsPrivate in vm.state.value.problems)
        assertEquals(false, vm.state.value.capabilities?.perAppRouting)
        val opts = vm.state.value.settings.toStartOptions(vm.state.value.capabilities!!)
        assertTrue(opts.includePackages.isEmpty(), "per-app must not reach the core when the capability is absent")
    }

    @Test fun `parseItems splits on common separators and dedupes`() {
        assertEquals(listOf("a.com", "b.com", "1.1.1.1"), SettingsViewModel.parseItems("a.com, b.com\n1.1.1.1; a.com"))
    }
}
