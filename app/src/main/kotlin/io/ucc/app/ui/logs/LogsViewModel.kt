package io.ucc.app.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.applogic.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LogsUiState(
    val entries: List<LogBuffer.Entry> = emptyList(),
    val minLevel: LogBuffer.Level = LogBuffer.Level.INFO,
    val total: Int = 0,
)

class LogsViewModel(private val buffer: LogBuffer) : ViewModel() {
    private val minLevel = MutableStateFlow(LogBuffer.Level.INFO)

    val state: StateFlow<LogsUiState> = combine(buffer.entries, minLevel) { all, min ->
        LogsUiState(entries = all.filter { it.level >= min }.asReversed(), minLevel = min, total = all.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState())

    fun setMinLevel(level: LogBuffer.Level) { minLevel.value = level }
    fun clear() = buffer.clear()
    /** Full buffer as text for the share sheet / clipboard (already redacted at the source). */
    fun exportText(): String = buffer.renderPlainText(minLevel.value)

    class Factory(private val buffer: LogBuffer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LogsViewModel(buffer) as T
    }
}
