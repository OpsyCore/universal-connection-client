package io.ucc.core.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point through which the application obtains a [CoreAdapter] without
 * naming a concrete engine. Each engine module (sing-box today, others later)
 * ships one implementation; the app picks one via build configuration.
 *
 * This is the Core boundary: everything above it (UI, Config Engine, Server
 * Manager, Smart Engine, VPN service) may depend on `engine-api` only.
 */
public interface CoreFactory {
    /** Stable id used in build config / settings, e.g. `"singbox"`. */
    public val id: String

    /** Human-readable description + version of the engine this factory creates. */
    public val descriptor: CoreDescriptor

    /** What this engine can do; used by UI to hide unsupported options. */
    public val capabilities: CoreCapabilities

    /** Licences of the engine and the third-party code compiled into it. */
    public val notices: List<ThirdPartyNotice>

    /** Creates the adapter. [platform] gives the engine access to host services in a core-agnostic way. */
    public fun create(platform: CorePlatform): CoreAdapter
}

/**
 * Host services handed to an engine at construction time. Deliberately
 * minimal: engines that need more must add optional, engine-agnostic hooks
 * here rather than importing app or VPN classes.
 */
public interface CorePlatform {
    /** Directory for engine state (databases, sockets, caches). */
    public val workingDirectory: java.io.File

    /** Directory that may be wiped at any time. */
    public val cacheDirectory: java.io.File

    /** Whether verbose engine diagnostics should be enabled. */
    public val debug: Boolean

    /** Underlying (non-VPN) network the engine should use for its own sockets and DNS, if the platform can tell. */
    public val underlyingNetwork: StateFlow<UnderlyingNetwork?>
}

/** Platform-neutral description of the network beneath the tunnel. */
public data class UnderlyingNetwork(
    /** Opaque handle understood by the platform layer (on Android: `android.net.Network`). */
    val handle: Any,
    val interfaceName: String,
    val interfaceIndex: Int,
    val expensive: Boolean,
)

/**
 * Optional engine hook: receives underlying-interface changes so it can bind
 * sockets / reset connections. Implemented by engines that need it; the
 * platform layer feeds it. Engines that do not need it simply don't implement it.
 */
public interface InterfaceObserver {
    public fun onDefaultInterface(name: String, index: Int, expensive: Boolean)
    public fun onDefaultInterfaceLost()
}

/** One third-party licence entry, shown in the app's licences screen and written to release notices. */
public data class ThirdPartyNotice(
    val component: String,
    val version: String,
    val license: String,
    val url: String,
    /** Extra terms beyond the SPDX licence, e.g. sing-box's naming clause. */
    val additionalTerms: String? = null,
)
