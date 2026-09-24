package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Platform boundaries of the shared application layer. Android implements them
 * with encrypted JSON files / SharedPreferences (app/data); other platforms
 * supply their own. None of these leak a platform type.
 */

/** Profile persistence. Implemented by `JsonProfileStore` (Android, AES-GCM file). */
interface ProfileStore {
    val profiles: StateFlow<List<ConnectionProfile>>
    fun current(): List<ConnectionProfile>
    suspend fun upsertAll(profiles: List<ConnectionProfile>)
    suspend fun deleteAll(ids: Collection<String>)
    /** One atomic write: apply [upserts] then remove [deleteIds]. */
    suspend fun apply(upserts: List<ConnectionProfile>, deleteIds: Collection<String>)
}

/** Subscription-record persistence. Implemented by `JsonSubscriptionStore` (Android). */
interface SubscriptionStore {
    val all: StateFlow<List<Subscription>>
    suspend fun byId(id: String): Subscription?
    suspend fun upsert(subscription: Subscription)
    suspend fun delete(id: String)
}

/** Selection state shared by Home and Servers; abstracted so view-models are testable without a platform context. */
interface SelectionStore {
    val selectedProfileIdFlow: StateFlow<String?>
    var selectedProfileId: String?

    /** true = Smart selection picks the server on connect; false = the user's [selectedProfileId] is used. */
    val smartModeFlow: StateFlow<Boolean>
    var smartMode: Boolean
}

/** Servers-screen list preferences (v1.0.2): persisted, non-secret, read by the view-model only. */
interface ServerListPrefs {
    /** Order rows by measured latency (best first; untested/failed last) instead of favourites + name. */
    val sortByLatencyFlow: StateFlow<Boolean>
    var sortByLatency: Boolean

    /** Hide rows whose latest evidence says the server is down (offline status or a failed test this session). */
    val hideFailedFlow: StateFlow<Boolean>
    var hideFailed: Boolean
}

/** In-memory [ServerListPrefs] for tests and platforms without persistence. */
class InMemoryServerListPrefs : ServerListPrefs {
    private val _sort = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _hide = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val sortByLatencyFlow: StateFlow<Boolean> = _sort
    override var sortByLatency: Boolean get() = _sort.value; set(v) { _sort.value = v }
    override val hideFailedFlow: StateFlow<Boolean> = _hide
    override var hideFailed: Boolean get() = _hide.value; set(v) { _hide.value = v }
}

/** Connection settings persistence (read synchronously by the tunnel host at start). No secrets live here. */
interface SettingsStore {
    val settings: StateFlow<ConnectionSettings>
    fun update(transform: (ConnectionSettings) -> ConnectionSettings)
}

class InMemorySettingsStore(initial: ConnectionSettings = ConnectionSettings()) : SettingsStore {
    override val settings: MutableStateFlow<ConnectionSettings> = MutableStateFlow(initial)
    override fun update(transform: (ConnectionSettings) -> ConnectionSettings) { settings.value = transform(settings.value) }
}
