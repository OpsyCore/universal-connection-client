package io.ucc.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.ucc.app.data.JsonProfileStore
import io.ucc.app.data.Preferences
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.manager.ConnectionEvent
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val selectedProfile: ConnectionProfile? = null,
    val profiles: List<ConnectionProfile> = emptyList(),
    val statistics: CoreStatistics? = null,
    val events: List<ConnectionEvent> = emptyList(),
    val coreName: String = "",
    val coreVersion: String = "",
)

class HomeViewModel(
    private val manager: ConnectionManager,
    private val store: JsonProfileStore,
    private val preferences: Preferences,
    coreName: String,
    coreVersion: String,
) : ViewModel() {

    private val selectedId = MutableStateFlow(preferences.selectedProfileId)

    private val recentEvents = manager.events
        .scan(emptyList<ConnectionEvent>()) { acc, e -> (acc + e).takeLast(MAX_EVENTS) }

    val uiState: StateFlow<HomeUiState> = combine(
        manager.state, store.profiles, selectedId, manager.statistics, recentEvents,
    ) { state, profiles, selected, stats, events ->
        HomeUiState(
            connection = state,
            selectedProfile = profiles.firstOrNull { it.id == selected } ?: profiles.firstOrNull(),
            profiles = profiles,
            statistics = stats,
            events = events.asReversed(),
            coreName = coreName,
            coreVersion = coreVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(coreName = coreName, coreVersion = coreVersion))

    fun select(profileId: String) {
        selectedId.value = profileId
        preferences.selectedProfileId = profileId
    }

    /** Called only after VPN permission has been confirmed by the activity. */
    fun connectSelected() {
        val id = uiState.value.selectedProfile?.id ?: return
        manager.connect(id)
    }

    fun disconnect() = manager.disconnect()

    private companion object {
        const val MAX_EVENTS = 50
    }

    class Factory(
        private val manager: ConnectionManager,
        private val store: JsonProfileStore,
        private val preferences: Preferences,
        private val coreName: String,
        private val coreVersion: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(manager, store, preferences, coreName, coreVersion) as T
    }
}
