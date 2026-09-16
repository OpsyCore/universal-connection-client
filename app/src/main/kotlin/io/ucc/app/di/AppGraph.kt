package io.ucc.app.di

import android.content.Context
import android.content.Intent
import io.ucc.app.BuildConfig
import io.ucc.app.MainActivity
import io.ucc.app.data.JsonProfileStore
import io.ucc.app.data.Preferences
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.manager.Clock
import io.ucc.core.engine.manager.ConnectionManager
import io.ucc.core.engine.manager.DefaultConnectionManager
import io.ucc.core.singbox.android.SingBoxCoreAdapter
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
    val profileStore = JsonProfileStore(app)
    val networkMonitor = AndroidNetworkMonitor(app)

    val core: CoreAdapter = SingBoxCoreAdapter(
        context = app,
        debug = BuildConfig.DEBUG,
        defaultNetwork = { networkMonitor.current.value },
    ).also { adapter ->
        networkMonitor.onInterface = adapter::onDefaultInterface
        networkMonitor.onInterfaceLost = adapter::onDefaultInterfaceLost
    }

    val connectionManager: ConnectionManager = DefaultConnectionManager(
        scope = appScope,
        core = core,
        tunnelHost = AndroidTunnelHost(app),
        profiles = profileStore,
        networkMonitor = networkMonitor,
        clock = object : Clock { override fun nowMs(): Long = System.currentTimeMillis() },
    )

    init {
        VpnServiceRegistry.connectionManager = connectionManager
        VpnServiceRegistry.stateForNotification = connectionManager.state
        VpnServiceRegistry.lastProfileStore = preferences
        VpnServiceRegistry.profileNameLookup = { id -> profileStore.byId(id)?.name }
        VpnServiceRegistry.launchIntentFactory = { ctx ->
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        appScope.launch { profileStore.load() }
    }
}
