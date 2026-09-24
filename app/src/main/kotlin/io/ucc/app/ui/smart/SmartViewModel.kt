package io.ucc.app.ui.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.applogic.SelectionStore
import io.ucc.applogic.SmartConnectionCoordinator
import io.ucc.core.smart.Candidate
import io.ucc.core.smart.HealthStatus
import io.ucc.core.smart.Selection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the Smart details screen: a candidate with its derived status and rank. */
data class SmartRow(val candidate: Candidate, val status: HealthStatus, val recommended: Boolean)

data class SmartUiState(
    val smartMode: Boolean = false,
    /** Ranked best-first when a selection exists; otherwise store order. */
    val rows: List<SmartRow> = emptyList(),
    val selection: Selection? = null,
    val transport: String? = null,
    val testingAll: Boolean = false,
    val unsupportedCount: Int = 0,
)

class SmartViewModel(
    private val smart: SmartConnectionCoordinator,
    private val selection: SelectionStore,
) : ViewModel() {
    private val testing = MutableStateFlow(false)
    private var job: Job? = null

    val state: StateFlow<SmartUiState> = combine(smart.candidates, selection.smartModeFlow, smart.transport, testing) { list, mode, tr, busy ->
        val sel = smart.select()
        val ranked = when (sel) {
            is Selection.Chosen -> sel.ranked
            is Selection.AllUnhealthy -> sel.ranked
            else -> null
        }
        val byId = list.associateBy { it.first.profile.id }
        val ordered = (ranked?.mapNotNull { byId[it.profile.id] } ?: list.filter { it.first.supported })
        val recommended = (sel as? Selection.Chosen)?.candidate?.profile?.id
        SmartUiState(
            smartMode = mode,
            rows = ordered.map { (c, s) -> SmartRow(c, s, c.profile.id == recommended) },
            selection = sel,
            transport = tr,
            testingAll = busy,
            unsupportedCount = list.count { !it.first.supported },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartUiState())

    fun setSmartMode(on: Boolean) { selection.smartMode = on }

    /** Tests all supported servers (bounded by the runner); tapping again cancels. */
    fun testAll() {
        job?.let { if (it.isActive) { it.cancel(); testing.value = false; return } }
        val ps = state.value.rows.map { it.candidate.profile }
        if (ps.isEmpty()) return
        job = viewModelScope.launch {
            testing.value = true
            try { smart.testAll(ps) { _, _ -> } } finally { testing.value = false }
        }
    }

    class Factory(private val smart: SmartConnectionCoordinator, private val selection: SelectionStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SmartViewModel(smart, selection) as T
    }
}
