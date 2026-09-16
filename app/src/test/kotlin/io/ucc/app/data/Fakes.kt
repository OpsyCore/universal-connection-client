package io.ucc.app.data

import io.ucc.core.config.CapabilityCheck
import io.ucc.core.config.ConfigImporter
import io.ucc.core.config.ImportPlanner
import io.ucc.core.config.parser.LinkParser
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.config.subscription.SubscriptionFetchResult
import io.ucc.core.config.subscription.SubscriptionFetcher
import io.ucc.core.config.subscription.SubscriptionInfo
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol

class FakeProfileStore : ProfileStore {
    val saved = LinkedHashMap<String, ConnectionProfile>()
    var failNextWrite = false
    override fun current(): List<ConnectionProfile> = saved.values.toList()
    override suspend fun upsertAll(profiles: List<ConnectionProfile>) {
        if (failNextWrite) { failNextWrite = false; throw java.io.IOException("disk full") }
        profiles.forEach { saved[it.id] = it }
    }
}

class FakeSubscriptionStore : SubscriptionStore {
    val saved = LinkedHashMap<String, Subscription>()
    override suspend fun byId(id: String): Subscription? = saved[id]
    override suspend fun upsert(subscription: Subscription) { saved[subscription.id] = subscription }
}

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
