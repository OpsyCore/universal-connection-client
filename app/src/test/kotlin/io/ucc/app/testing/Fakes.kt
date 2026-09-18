package io.ucc.app.testing

/*
 * Android-side copies of the shared fakes (core/app-logic commonTest). Kept here so app view-model
 * tests need no cross-module test-fixture plumbing. Keep in sync with core/app-logic/src/commonTest/.../Fakes.kt.
 */

import io.ucc.applogic.ImportRepository
import io.ucc.applogic.ProfileStore
import io.ucc.applogic.SelectionStore
import io.ucc.applogic.SubscriptionStore
import io.ucc.core.config.CapabilityCheck
import io.ucc.core.config.ConfigImporter
import io.ucc.core.config.ImportPlanner
import io.ucc.core.config.parser.LinkParser
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.config.subscription.SubscriptionFetchResult
import io.ucc.core.config.subscription.SubscriptionFetcher
import io.ucc.core.config.subscription.SubscriptionInfo
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.manager.ConnectionEvent
import io.ucc.core.engine.manager.ConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol

class FakeProfileStore : ProfileStore {
    val saved = LinkedHashMap<String, ConnectionProfile>()
    var failNextWrite = false
    var writes = 0
    private val _flow = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    override val profiles: StateFlow<List<ConnectionProfile>> = _flow
    override fun current(): List<ConnectionProfile> = saved.values.toList()
    override suspend fun upsertAll(profiles: List<ConnectionProfile>) = apply(profiles, emptyList())
    override suspend fun deleteAll(ids: Collection<String>) = apply(emptyList(), ids)
    override suspend fun apply(upserts: List<ConnectionProfile>, deleteIds: Collection<String>) {
        if (failNextWrite) { failNextWrite = false; throw java.io.IOException("disk full") }
        writes++
        upserts.forEach { saved[it.id] = it }
        deleteIds.forEach { saved.remove(it) }
        _flow.value = current()
    }
}

class FakeSubscriptionStore : SubscriptionStore {
    val saved = LinkedHashMap<String, Subscription>()
    private val _flow = MutableStateFlow<List<Subscription>>(emptyList())
    override val all: StateFlow<List<Subscription>> = _flow
    override suspend fun byId(id: String): Subscription? = saved[id]
    override suspend fun upsert(subscription: Subscription) { saved[subscription.id] = subscription; _flow.value = saved.values.toList() }
    override suspend fun delete(id: String) { saved.remove(id); _flow.value = saved.values.toList() }
}

/** Minimal manager: only [state] matters for the data layer; commands record calls. */
class FakeConnectionManager(initial: ConnectionState = ConnectionState.Disconnected) : ConnectionManager {
    override val state = MutableStateFlow(initial)
    override val transitions: Flow<ConnectionState> = state
    override val statistics = MutableStateFlow<CoreStatistics?>(null)
    override val events: Flow<ConnectionEvent> = emptyFlow()
    val connected = ArrayList<String>()
    var disconnects = 0
    override fun connect(profileId: String, options: CoreStartOptions?) { connected += profileId }
    override fun disconnect() { disconnects++ }
    override fun attachRunningTunnel(profileId: String, sinceEpochMs: Long) {}
}

class FakeSelection : SelectionStore {
    private val _f = MutableStateFlow<String?>(null)
    override val selectedProfileIdFlow: StateFlow<String?> = _f
    override var selectedProfileId: String?
        get() = _f.value
        set(value) { _f.value = value }
    private val _smart = MutableStateFlow(false)
    override val smartModeFlow: StateFlow<Boolean> = _smart
    override var smartMode: Boolean
        get() = _smart.value
        set(value) { _smart.value = value }
}

fun testImporter(ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "id-$it" }.iterator()) =
    ConfigImporter(LinkParser({ ids.next() }, { 1_000L }))

class FakeFetcher : SubscriptionFetcher {
    var body: String = ""
    var info: SubscriptionInfo? = null
    var name: String? = null
    var error: SubscriptionFetchError? = null
    var lastUrl: String? = null
    override suspend fun fetch(url: String): SubscriptionFetchResult {
        lastUrl = url
        error?.let { throw it }
        return SubscriptionFetchResult(body, info, name, null)
    }
}

val testCapabilities = CoreCapabilities(
    protocols = setOf(Protocol.VLESS, Protocol.TROJAN, Protocol.SHADOWSOCKS, Protocol.VMESS),
    transports = setOf("tcp", "ws", "grpc", "http", "httpupgrade"),
    reality = true, utlsFingerprints = true, perAppRouting = true, ruleSets = true, fakeIp = true, hotReload = true,
)

fun newRepository(
    profiles: FakeProfileStore = FakeProfileStore(),
    subs: FakeSubscriptionStore = FakeSubscriptionStore(),
    fetcher: FakeFetcher = FakeFetcher(),
    ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "id-$it" }.iterator(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
): ImportRepository = ImportRepository(
    importer = ConfigImporter(LinkParser({ ids.next() }, { 1_000L })),
    planner = ImportPlanner(CapabilityCheck(testCapabilities)),
    profiles = profiles,
    subscriptions = subs,
    fetcher = fetcher,
    now = { 5_000L },
    parseDispatcher = dispatcher,
)
