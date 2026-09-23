package io.ucc.core.engine

import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over a proxy core (sing-box today; others later).
 *
 * The adapter owns the core process/instance and its TUN file descriptor
 * lifecycle. It never talks to Android UI. All methods are main-safe; blocking
 * work happens on the adapter's own dispatcher.
 */
public interface CoreAdapter {
    public val descriptor: CoreDescriptor

    /** Static capabilities used by validation and by the UI to hide unsupported options. */
    public val capabilities: CoreCapabilities

    /**
     * Starts the core for [profile] using [tun] to obtain the TUN descriptor.
     * Returns normally once the core reports it is running. Throws
     * [CoreException] with a classified [ConnectionError] otherwise.
     */
    public suspend fun start(profile: ConnectionProfile, options: CoreStartOptions, tun: TunProvider)

    /** Reloads with a new profile without tearing down the TUN when the core supports it. */
    public suspend fun reload(profile: ConnectionProfile, options: CoreStartOptions)

    public suspend fun stop()

    /** Called by the platform when the default network changed; the core should reset its connections. */
    public suspend fun onNetworkChanged()

    /** Called on device sleep/wake so the core can pause timers. */
    public suspend fun onPause()
    public suspend fun onResume()

    /** Real per-second traffic and connection statistics; emits only while running. */
    public val statistics: Flow<CoreStatistics>

    /** Core log lines, already redacted by the adapter. */
    public val logs: Flow<CoreLogLine>

    /** Asynchronous core events (fatal error, group change…). */
    public val events: Flow<CoreEvent>

    /**
     * Measures HTTP RTT through the running core to [url]. Real measurement;
     * throws if the core is not running.
     */
    public suspend fun urlTest(url: String, timeoutMs: Long): Long
}

public data class CoreDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
)

public data class CoreCapabilities(
    val protocols: Set<Protocol>,
    val transports: Set<String>,
    val reality: Boolean,
    val utlsFingerprints: Boolean,
    val perAppRouting: Boolean,
    val ruleSets: Boolean,
    val fakeIp: Boolean,
    val hotReload: Boolean,
)

public data class CoreStartOptions(
    val mtu: Int = 9000,
    val ipv6: Boolean = true,
    /**
     * Leak protection while the tunnel is up: the core rejects traffic that
     * would bypass it (sing-box `strict_route`). This is *not* a system kill
     * switch — when the VPN is down, only Android's "Block connections without
     * VPN" (always-on) setting blocks traffic. The UI must say so.
     */
    val strictRoute: Boolean = true,
    val includePackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val logLevel: String = "info",
    /** Remote (through-tunnel) DNS override; null = profile's own or the core default. */
    val remoteDns: String? = null,
    /** DNS used for direct traffic and for resolving the proxy server itself; null = system resolver. */
    val directDns: String? = null,
    /** Private/LAN destinations go direct instead of through the proxy. */
    val bypassPrivate: Boolean = true,
    /** User routing rules, evaluated in order before the final (proxy) route. */
    val rules: List<RoutingRule> = emptyList(),
    /**
     * Split the proxy's TLS ClientHello (sing-box `tls.fragment` + `tls.record_fragment`).
     * Applies to TCP-based TLS outbounds only (not QUIC protocols, not REALITY). Default off.
     */
    val tlsFragment: Boolean = false,
    /** Reject QUIC (UDP/443 HTTP/3) inside the tunnel so apps fall back to TCP; sing-box `{protocol: quic, action: reject}`. Default off. */
    val blockQuic: Boolean = false,
    /**
     * Expose a SOCKS5/HTTP proxy for other devices on the local network (sing-box `mixed` inbound on
     * `0.0.0.0:[lanProxyPort]`). Unauthenticated: an open proxy for everyone on the LAN. Default off.
     */
    val lanProxy: Boolean = false,
    val lanProxyPort: Int = DEFAULT_LAN_PROXY_PORT,
    /**
     * FakeIP: A/AAAA answers for tunnelled queries come from a reserved range so routing sees domains
     * and no upstream lookup happens before the connection (sing-box `fakeip` DNS server). Default off.
     */
    val fakeDns: Boolean = false,
    /** Extra JSON fragments the routing/DNS layers contribute (rule-sets, Phase 6). Override the typed fields when set. */
    val routingConfig: String? = null,
    val dnsConfig: String? = null,
) {
    public companion object {
        public const val DEFAULT_LAN_PROXY_PORT: Int = 2080
        public val LAN_PROXY_PORT_RANGE: IntRange = 1024..65535
    }
}

/**
 * One core-agnostic routing rule: all non-empty matchers are OR-ed by the core
 * (sing-box semantics: items inside one rule of the same kind are OR-ed; kinds
 * are AND-ed — we therefore emit one rule per matcher kind).
 */
public data class RoutingRule(
    val action: RouteAction,
    val domains: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val domainKeywords: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = domains.isEmpty() && domainSuffixes.isEmpty() && domainKeywords.isEmpty() && ipCidrs.isEmpty()
}

public enum class RouteAction { DIRECT, PROXY, BLOCK }

public data class CoreStatistics(
    val uplinkBytesPerSecond: Long,
    val downlinkBytesPerSecond: Long,
    val uplinkTotalBytes: Long,
    val downlinkTotalBytes: Long,
    val connectionsIn: Int,
    val connectionsOut: Int,
    val memoryBytes: Long,
    val goroutines: Int,
)

public data class CoreLogLine(val level: Int, val message: String, val epochMs: Long)

public sealed class CoreEvent {
    public data class Fatal(val error: ConnectionError) : CoreEvent()
    public data class Info(val message: String) : CoreEvent()
}

public class CoreException(public val error: ConnectionError, cause: Throwable? = null) :
    Exception(error.technicalDetail, cause)
