package io.ucc.core.singbox.apple

import io.ucc.core.engine.CorePlatform
import io.ucc.core.engine.UnderlyingNetwork
import io.ucc.core.engine.manager.Clock
import io.ucc.core.engine.manager.StartOptionsProvider
import io.ucc.core.platform.currentTimeMillis
import io.ucc.iosinfra.PathNetworkMonitor
import io.ucc.iosvpn.AppGroupStorage
import io.ucc.iosvpn.TunnelEngine
import io.ucc.iosvpn.TunnelEngineContext
import io.ucc.iosvpn.TunnelEngineFactory
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSTemporaryDirectory

/**
 * Wires the Apple engine into the packet tunnel provider:
 *
 *   UccPacketTunnelProvider → TunnelEngineFactory → AppleSingBoxTunnelEngine
 *       → AppleSingBoxCoreFactory(AppleLibboxServiceFactory) → AppleSingBoxCoreAdapter → Libbox
 *
 * Profiles and settings come from the App Group stores (same files the host app writes),
 * the underlying network from the push-based [PathNetworkMonitor]. Call [install] once at
 * extension start-up (before the system instantiates the provider). With no Libbox linked
 * the engine starts, fails inside `CoreAdapter.start` with `Libbox unavailable`, cleans up
 * and reports `ProviderStartupFailure` — never a running tunnel.
 */
public object AppleTunnelEngineBootstrap {
    private val clock = object : Clock { override fun nowMs(): Long = currentTimeMillis() }

    public fun install(
        storage: AppGroupStorage = AppGroupStorage(),
        libbox: LibboxServiceFactory = AppleLibboxServiceFactory,
        debug: Boolean = false,
    ) {
        TunnelEngineFactory.install { context -> create(context, storage, libbox, debug) }
    }

    public fun create(context: TunnelEngineContext, storage: AppGroupStorage, libbox: LibboxServiceFactory, debug: Boolean): TunnelEngine {
        val monitor = PathNetworkMonitor()
        val factory = AppleSingBoxCoreFactory(libbox, clock)
        val platform = object : CorePlatform {
            override val workingDirectory: String = storage.dataDirectory + "/core"
            override val cacheDirectory: String = NSTemporaryDirectory().trimEnd('/')
            override val debug: Boolean = debug
            override val underlyingNetwork: StateFlow<UnderlyingNetwork?> = monitor.underlying
        }
        return AppleSingBoxTunnelEngine(
            adapter = factory.create(platform),
            profiles = storage.profiles,
            startOptions = StartOptionsProvider { storage.settings.settings.value.toStartOptions(factory.capabilities) },
            tunnelHost = context.tunnelHost,
        )
    }
}
