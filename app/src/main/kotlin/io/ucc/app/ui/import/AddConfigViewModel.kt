package io.ucc.app.ui.import

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.app.data.ImportRepository
import io.ucc.app.data.SubscriptionPlan
import io.ucc.core.config.ImportPlan
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Add Configuration flow: one ViewModel, three phases.
 *   Input (text/subscription URL entry, or external source) → Preview (plan) → Done.
 * No auto-connect: saving only persists profiles.
 */
sealed class AddConfigUiState {
    data class Input(
        val text: String = "",
        val subscriptionUrl: String = "",
        val busy: Boolean = false,
        val error: AddConfigError? = null,
    ) : AddConfigUiState()

    data class Preview(
        val plan: ImportPlan,
        val previews: List<ProfilePreview>,
        val selected: Set<String>,
        val subscription: SubscriptionPlan?,
        val sourceLabel: SourceLabel,
        val saving: Boolean = false,
    ) : AddConfigUiState() {
        val canSave: Boolean get() = !saving && plan.items.any { it.profile.id in selected && it.status.isSavable }
    }

    data class Done(val savedCount: Int, val skippedCount: Int) : AddConfigUiState()
}

enum class SourceLabel { PASTE, CLIPBOARD, QR, FILE, SUBSCRIPTION }

sealed class AddConfigError {
    data object EmptyInput : AddConfigError()
    /** No profile could be built; [unreadable] fragments were rejected (details never contain secrets). */
    data class NothingRecognised(val unreadable: Int) : AddConfigError()
    data object ClipboardEmpty : AddConfigError()
    data class FileUnreadable(val reason: String) : AddConfigError()
    data class FileTooLarge(val limitBytes: Long) : AddConfigError()
    data class Subscription(val error: SubscriptionFetchError) : AddConfigError()
    data class Unexpected(val kind: String) : AddConfigError()
}

class AddConfigViewModel(private val repo: ImportRepository) : ViewModel() {
    private val _state = MutableStateFlow<AddConfigUiState>(AddConfigUiState.Input())
    val state: StateFlow<AddConfigUiState> = _state

    fun onTextChanged(text: String) = _state.update { s -> if (s is AddConfigUiState.Input) s.copy(text = text, error = null) else s }
    fun onSubscriptionUrlChanged(url: String) = _state.update { s -> if (s is AddConfigUiState.Input) s.copy(subscriptionUrl = url, error = null) else s }

    fun submitText(label: SourceLabel = SourceLabel.PASTE) {
        val s = _state.value as? AddConfigUiState.Input ?: return
        importText(s.text, ProfileSource.Manual, label)
    }

    /** Called by the UI with clipboard content (read on the main thread by the UI layer). */
    fun importClipboard(text: String?) {
        if (text.isNullOrBlank()) { setError(AddConfigError.ClipboardEmpty); return }
        importText(text, ProfileSource.Clipboard, SourceLabel.CLIPBOARD)
    }

    fun importQr(payload: String) = importText(payload, ProfileSource.QrCode, SourceLabel.QR)

    fun importFile(fileName: String, content: String) = importText(content, ProfileSource.File(fileName), SourceLabel.FILE)

    fun fileError(error: AddConfigError) = setError(error)

    fun submitSubscription() {
        val s = _state.value as? AddConfigUiState.Input ?: return
        val url = s.subscriptionUrl.trim()
        if (url.isEmpty()) { setError(AddConfigError.EmptyInput); return }
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val sp = repo.planFromSubscription(url)
                if (sp.plan.items.isEmpty()) { backToInput(AddConfigError.NothingRecognised(sp.plan.failures.size)); return@launch }
                showPreview(sp.plan, sp, SourceLabel.SUBSCRIPTION)
            } catch (e: SubscriptionFetchError) {
                backToInput(AddConfigError.Subscription(e))
            } catch (e: Exception) {
                backToInput(AddConfigError.Unexpected(e.javaClass.simpleName))
            }
        }
    }

    fun toggle(profileId: String) = _state.update { s ->
        if (s !is AddConfigUiState.Preview) s
        else s.copy(selected = if (profileId in s.selected) s.selected - profileId else s.selected + profileId)
    }

    fun selectAllSavable(select: Boolean) = _state.update { s ->
        if (s !is AddConfigUiState.Preview) s
        else s.copy(selected = if (select) s.plan.items.filter { it.status.isSavable }.map { it.profile.id }.toSet() else emptySet())
    }

    fun confirm() {
        val s = _state.value as? AddConfigUiState.Preview ?: return
        if (!s.canSave) return
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            try {
                val r = repo.commit(s.plan, s.selected, s.subscription)
                _state.value = AddConfigUiState.Done(savedCount = r.savedIds.size, skippedCount = r.skipped)
            } catch (e: Exception) {
                _state.value = s.copy(saving = false)
                // Persist failure is surfaced by returning to input with a generic error; nothing was partially applied (store is atomic).
                _state.value = AddConfigUiState.Input(error = AddConfigError.Unexpected(e.javaClass.simpleName))
            }
        }
    }

    fun cancelPreview() { _state.value = AddConfigUiState.Input() }

    private fun importText(text: String, source: ProfileSource, label: SourceLabel) {
        if (text.isBlank()) { setError(AddConfigError.EmptyInput); return }
        val prev = _state.value as? AddConfigUiState.Input ?: AddConfigUiState.Input()
        _state.value = prev.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val plan = repo.planFromText(text, source)
                if (plan.items.isEmpty()) { backToInput(AddConfigError.NothingRecognised(plan.failures.size), keep = prev); return@launch }
                showPreview(plan, null, label)
            } catch (e: Exception) {
                backToInput(AddConfigError.Unexpected(e.javaClass.simpleName), keep = prev)
            }
        }
    }

    private fun showPreview(plan: ImportPlan, sub: SubscriptionPlan?, label: SourceLabel) {
        _state.value = AddConfigUiState.Preview(
            plan = plan,
            previews = plan.items.map { ProfilePreview.of(it.profile) },
            selected = plan.items.filter { it.selectedByDefault }.map { it.profile.id }.toSet(),
            subscription = sub,
            sourceLabel = label,
        )
    }

    private fun setError(e: AddConfigError) = _state.update { s -> (s as? AddConfigUiState.Input ?: AddConfigUiState.Input()).copy(error = e, busy = false) }

    private fun backToInput(e: AddConfigError, keep: AddConfigUiState.Input? = null) {
        val base = keep ?: (_state.value as? AddConfigUiState.Input) ?: AddConfigUiState.Input()
        _state.value = base.copy(busy = false, error = e)
    }

    class Factory(private val repo: ImportRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AddConfigViewModel(repo) as T
    }
}
