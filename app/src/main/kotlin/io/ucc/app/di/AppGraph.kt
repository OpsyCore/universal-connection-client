package io.ucc.app.di

import android.content.Context
import android.content.Intent
import io.ucc.app.BuildConfig
import io.ucc.app.MainActivity
import io.ucc.applogic.ImportRepository
import io.ucc.app.data.JsonProfileStore
import io.ucc.app.data.JsonSubscriptionStore
import io.ucc.applogic.LogBuffer
import io.ucc.app.data.PrefsSettingsStore
import io.ucc.applogic.SettingsStore
import io.ucc.applogic.ServerRepository
import io.ucc.applogic.SubscriptionRefresher
import io.ucc.app.work.SubscriptionRefreshWorker
import io.ucc.core.config.CapabilityCheck
import io.ucc.core.config.ConfigImporter
import io.ucc.core.config.ImportPlanner
import io.ucc.core.config.subscription.HttpSubscriptionFetcher
import io.ucc.app.data.Notices
import io.ucc.app.data.Preferences
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.manager.Clock
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.engine.manager.DefaultConnectionManager
import io.ucc.app.core.CoreFactories
import io.ucc.core.engine.CoreFactory
import io.ucc.core.engine.InterfaceObserver
import io.ucc.core.vpn.AndroidCorePlatform
import io.ucc.core.vpn.AndroidNetworkMonitor
import io.ucc.core.vpn.AndroidTunnelHost
import io.ucc.core.vpn.VpnServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Manual dependency graph. Constructed once by [io.ucc.app.UccApplication].
 * Kept explicit on purpose: the graph is small and a DI framework would add
 * annotation processing without paying for itself yet.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferences = Preferences(app)
    /** Lazy: AppCompat restores stored locales when the first activity attaches, so read it after that. */
    val languageStore: io.ucc.applogic.LanguageStore by lazy { io.ucc.app.data.AppCompatLanguageStore() }
    val settingsStore: SettingsStore = PrefsSettingsStore(app)
    val profileStore = JsonProfileStore(app)
    val networkMonitor = AndroidNetworkMonitor(app)

    /** Selected via BuildConfig.CORE_ID; the only engine-specific reference lives in [CoreFactories]. */
    val coreFactory: CoreFactory = CoreFactories.selected(app)
    val corePlatform = AndroidCorePlatform(app, debug = BuildConfig.DEBUG, networkMonitor = networkMonitor)

    val core: CoreAdapter = coreFactory.create(corePlatform).also { adapter ->
        // Optional capability: engines that track the underlying interface opt in by implementing InterfaceObserver.
        (adapter as? InterfaceObserver)?.let { networkMonitor.interfaceObserver = it }
    }

    val notices = Notices(coreFactory)

    val subscriptionStore = JsonSubscriptionStore(app)

    private val subscriptionFetcher = HttpSubscriptionFetcher(userAgent = "UniversalConnectionClient/${BuildConfig.VERSION_NAME} (${coreFactory.descriptor.displayName}/${coreFactory.descriptor.version})")

    /** Config Engine wiring: parsers from :core:config, capabilities from the selected core. No engine types involved. */
    val importRepository = ImportRepository(
        importer = ConfigImporter(),
        planner = ImportPlanner(CapabilityCheck(core.capabilities)),
        profiles = profileStore,
        subscriptions = subscriptionStore,
        fetcher = subscriptionFetcher,
    )

    val connectionManager: ConnectionManager = DefaultConnectionManager(
        scope = appScope,
        core = core,
        tunnelHost = AndroidTunnelHost(app),
        profiles = profileStore,
        networkMonitor = networkMonitor,
        clock = object : Clock { override fun nowMs(): Long = System.currentTimeMillis() },
        startOptions = { settingsStore.settings.value.toStartOptions(core.capabilities) },
    )

    /** Bounded in-memory log buffer (core lines are already redacted by the adapter; manager events are redacted by design). */
    val logBuffer = LogBuffer(appScope, core.logs, connectionManager.events)

    /** Smart selection: pure policy from :core:smart, persistence + manager glue here. */
    val healthStore = io.ucc.app.data.JsonServerHealthStore(app)
    /**
     * Connection tester: real HTTP delay through the core's probe (v1.0.2) when the engine offers one and the
     * setting is on; TCP handshake otherwise. Switching is per call, so the Settings toggle applies at once.
     */
    private val tcpTester = io.ucc.core.smart.TcpConnectionTester()
    private val httpTester: io.ucc.core.smart.HttpDelayConnectionTester? = (core as? io.ucc.core.engine.CoreDelayProbe)?.let { probe ->
        io.ucc.core.smart.HttpDelayConnectionTester(
            probe = probe,
            startOptions = { settingsStore.settings.value.toStartOptions(core.capabilities) },
            url = { settingsStore.settings.value.effectiveDelayTestUrl },
            fallback = tcpTester,
        )
    }
    val connectionTester: io.ucc.core.smart.ConnectionTester = object : io.ucc.core.smart.BatchConnectionTester {
        private fun active(): io.ucc.core.smart.ConnectionTester = httpTester?.takeIf { settingsStore.settings.value.realDelayTest } ?: tcpTester
        override suspend fun test(profile: io.ucc.core.model.ConnectionProfile) = active().test(profile)
        override suspend fun <T> batch(profiles: List<io.ucc.core.model.ConnectionProfile>, block: suspend () -> T): T {
            val t = active()
            return if (t is io.ucc.core.smart.BatchConnectionTester) t.batch(profiles, block) else block()
        }
    }
    val healthRunner = io.ucc.core.smart.HealthCheckRunner(
        tester = connectionTester,
        store = healthStore,
        clock = { System.currentTimeMillis() },
        networkTransport = { smart.transport.value },
    )
    val smart: io.ucc.applogic.SmartConnectionCoordinator by lazy {
        io.ucc.applogic.SmartConnectionCoordinator(
            scope = appScope, manager = connectionManager, profiles = profileStore, health = healthStore, runner = healthRunner,
            capabilities = CapabilityCheck(core.capabilities), selection = preferences, networkMonitor = networkMonitor,
        )
    }
    val serverRepository = ServerRepository(profileStore, subscriptionStore, connectionManager)
    val subscriptionRefresher = SubscriptionRefresher(
        importer = ConfigImporter(),
        fetcher = subscriptionFetcher,
        profiles = profileStore,
        subscriptions = subscriptionStore,
        manager = connectionManager,
    )

    /** Completes once health, profile and subscription stores have been read from disk. */
    private val storesLoaded: kotlinx.coroutines.Job

    /** Suspends until the persisted stores are in memory. Call before any write that merges into a store. */
    suspend fun awaitStoresLoaded() = storesLoaded.join()

    init {
        VpnServiceRegistry.connectionManager = connectionManager
        VpnServiceRegistry.stateForNotification = connectionManager.state
        VpnServiceRegistry.statisticsForNotification = connectionManager.statistics
        VpnServiceRegistry.bootInputs = object : io.ucc.core.vpn.BootInputs {
            override fun autoConnectEnabled() = settingsStore.settings.value.autoConnectOnBoot
            override fun selectedProfileId() = preferences.selectedProfileId
        }
        VpnServiceRegistry.notificationSpeedEnabled = settingsStore.settings
            .map { it.notificationSpeed }
            .stateIn(appScope, SharingStarted.Eagerly, settingsStore.settings.value.notificationSpeed)
        VpnServiceRegistry.lastProfileStore = preferences
        VpnServiceRegistry.profileNameLookup = { id -> profileStore.byId(id)?.name }
        VpnServiceRegistry.launchIntentFactory = { ctx ->
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Health before profiles: the coordinator prunes health against the profile list once it is non-empty.
        storesLoaded = appScope.launch { healthStore.load(); profileStore.load(); subscriptionStore.load() }
        smart // start observing the manager

        // The "public free servers" list seeded by the 1.0.3 release candidates was withdrawn: remove it (and its
        // servers) once on upgrade. Runs after the stores are loaded — the JSON stores start empty in memory and
        // every write persists the in-memory list, so a write racing the load would overwrite the files on disk.
        appScope.launch {
            awaitStoresLoaded()
            for (id in io.ucc.applogic.DefaultSubscription.toRemove(subscriptionStore.all.value.map { it.id })) {
                val r = serverRepository.deleteSubscription(id)
                android.util.Log.i("SubRefresh", "withdrawn default subscription removed: deleted=${r.deleted} blockedActive=${r.blockedActive}")
            }
        }
        // Subscription auto-update (v1.0.2): periodic job follows the settings interval; optional refresh on foreground.
        appScope.launch {
            settingsStore.settings.map { it.subscriptionUpdateIntervalHours }.distinctUntilChanged().collect { SubscriptionRefreshWorker.schedule(app, it) }
        }
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                if (!settingsStore.settings.value.subscriptionUpdateOnOpen) return
                appScope.launch {
                    awaitStoresLoaded()
                    val outcomes = subscriptionRefresher.refreshAllDue(minIntervalMs = io.ucc.applogic.ConnectionSettings.ON_OPEN_MIN_INTERVAL_MS)
                    if (outcomes.isNotEmpty()) android.util.Log.i("SubRefresh", "on-open refresh: ${outcomes.size} due, ${outcomes.count { it !is SubscriptionRefresher.Outcome.Updated }} failed")
                }
            }
        })
    }
}
