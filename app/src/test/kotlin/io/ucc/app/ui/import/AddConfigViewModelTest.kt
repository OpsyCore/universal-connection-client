package io.ucc.app.ui.import

import io.ucc.app.testing.FakeFetcher
import io.ucc.app.testing.FakeProfileStore
import io.ucc.app.testing.newRepository
import io.ucc.core.config.subscription.SubscriptionFetchError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun `paste → preview → confirm → done, no connection triggered`() = runTest(dispatcher) {
        val store = FakeProfileStore()
        val vm = AddConfigViewModel(newRepository(profiles = store, dispatcher = dispatcher))
        vm.onTextChanged("trojan://pw@1.2.3.4:443#A\ntrojan://pw@1.2.3.5:443#B")
        vm.submitText()
        advanceUntilIdle()
        val preview = assertIs<AddConfigUiState.Preview>(vm.state.value)
        assertEquals(2, preview.selected.size)
        assertEquals(SourceLabel.PASTE, preview.sourceLabel)
        assertTrue(preview.canSave)
        vm.toggle(preview.plan.items[1].profile.id)
        vm.confirm()
        advanceUntilIdle()
        val done = assertIs<AddConfigUiState.Done>(vm.state.value)
        assertEquals(1, done.savedCount)
        assertEquals(listOf("A"), store.current().map { it.name })
    }

    @Test fun `empty and unrecognised input produce errors and stay in Input`() = runTest(dispatcher) {
        val vm = AddConfigViewModel(newRepository(dispatcher = dispatcher))
        vm.submitText(); advanceUntilIdle()
        assertIs<AddConfigError.EmptyInput>((vm.state.value as AddConfigUiState.Input).error)
        vm.onTextChanged("hello there"); vm.submitText(); advanceUntilIdle()
        val s = assertIs<AddConfigUiState.Input>(vm.state.value)
        assertIs<AddConfigError.NothingRecognised>(s.error)
        assertEquals("hello there", s.text, "user text is preserved on failure")
    }

    @Test fun `clipboard empty is reported and clipboard text is imported with clipboard label`() = runTest(dispatcher) {
        val vm = AddConfigViewModel(newRepository(dispatcher = dispatcher))
        vm.importClipboard(null); advanceUntilIdle()
        assertIs<AddConfigError.ClipboardEmpty>((vm.state.value as AddConfigUiState.Input).error)
        vm.importClipboard("trojan://pw@1.2.3.4:443#C"); advanceUntilIdle()
        assertEquals(SourceLabel.CLIPBOARD, assertIs<AddConfigUiState.Preview>(vm.state.value).sourceLabel)
    }

    @Test fun `qr and file entry points label their source`() = runTest(dispatcher) {
        val vm = AddConfigViewModel(newRepository(dispatcher = dispatcher))
        vm.importQr("trojan://pw@1.2.3.4:443#Q"); advanceUntilIdle()
        assertEquals(SourceLabel.QR, assertIs<AddConfigUiState.Preview>(vm.state.value).sourceLabel)
        vm.cancelPreview()
        vm.importFile("s.txt", "trojan://pw@1.2.3.4:443#F"); advanceUntilIdle()
        assertEquals(SourceLabel.FILE, assertIs<AddConfigUiState.Preview>(vm.state.value).sourceLabel)
    }

    @Test fun `subscription errors map to typed error and return to input`() = runTest(dispatcher) {
        val fetcher = FakeFetcher().apply { error = SubscriptionFetchError.InvalidUrl("only https") }
        val vm = AddConfigViewModel(newRepository(fetcher = fetcher, dispatcher = dispatcher))
        vm.onSubscriptionUrlChanged("http://insecure"); vm.submitSubscription(); advanceUntilIdle()
        val s = assertIs<AddConfigUiState.Input>(vm.state.value)
        val e = assertIs<AddConfigError.Subscription>(s.error)
        assertIs<SubscriptionFetchError.InvalidUrl>(e.error)
        assertFalse(s.busy)
    }

    @Test fun `subscription happy path reaches preview with subscription attached`() = runTest(dispatcher) {
        val fetcher = FakeFetcher().apply { body = "trojan://pw@1.2.3.4:443#S" }
        val vm = AddConfigViewModel(newRepository(fetcher = fetcher, dispatcher = dispatcher))
        vm.onSubscriptionUrlChanged("https://p.example/sub"); vm.submitSubscription(); advanceUntilIdle()
        val p = assertIs<AddConfigUiState.Preview>(vm.state.value)
        assertEquals(SourceLabel.SUBSCRIPTION, p.sourceLabel)
        assertEquals("https://p.example/sub", p.subscription?.url)
    }

    @Test fun `select all and none respect savability`() = runTest(dispatcher) {
        val vm = AddConfigViewModel(newRepository(dispatcher = dispatcher))
        vm.onTextChanged("trojan://pw@1.2.3.4:443#A\nhy2://pw@h.example.com:443#unsupported"); vm.submitText(); advanceUntilIdle()
        val p0 = assertIs<AddConfigUiState.Preview>(vm.state.value)
        assertEquals(1, p0.selected.size)
        vm.selectAllSavable(true)
        assertEquals(2, (vm.state.value as AddConfigUiState.Preview).selected.size, "unsupported is savable, so selectable")
        vm.selectAllSavable(false)
        assertFalse((vm.state.value as AddConfigUiState.Preview).canSave)
    }

    @Test fun `persist failure returns to input with an error and nothing saved`() = runTest(dispatcher) {
        val store = FakeProfileStore().apply { failNextWrite = true }
        val vm = AddConfigViewModel(newRepository(profiles = store, dispatcher = dispatcher))
        vm.onTextChanged("trojan://pw@1.2.3.4:443#A"); vm.submitText(); advanceUntilIdle()
        vm.confirm(); advanceUntilIdle()
        assertIs<AddConfigError.Unexpected>(assertIs<AddConfigUiState.Input>(vm.state.value).error)
        assertTrue(store.current().isEmpty())
    }
}
