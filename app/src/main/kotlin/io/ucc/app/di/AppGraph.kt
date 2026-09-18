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
    val healthRunner = io.ucc.core.smart.HealthCheckRunner(
        tester = io.ucc.core.smart.TcpConnectionTester(),
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

    init {
        VpnServiceRegistry.connectionManager = connectionManager
        VpnServiceRegistry.stateForNotification = connectionManager.state
        VpnServiceRegistry.lastProfileStore = preferences
        VpnServiceRegistry.profileNameLookup = { id -> profileStore.byId(id)?.name }
        VpnServiceRegistry.launchIntentFactory = { ctx ->
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Health before profiles: the coordinator prunes health against the profile list once it is non-empty.
        appScope.launch { healthStore.load(); profileStore.load(); subscriptionStore.load() }
        smart // start observing the manager

        SubscriptionRefreshWorker.schedule(app)
    }
}
