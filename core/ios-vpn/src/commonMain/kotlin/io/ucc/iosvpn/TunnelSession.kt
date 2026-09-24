package io.ucc.iosvpn

import io.ucc.core.engine.manager.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Provider-side lifecycle, framework-free so it is unit-tested on the JVM. The
 * `NEPacketTunnelProvider` subclass in iosMain delegates every callback here and
 * only performs the Apple calls (`setTunnelNetworkSettings`, completion handlers).
 *
 * Phase 6: the "engine" is a [TunnelEngine] port; the only implementation wired is
 * [TunnelEngine.None], which refuses to run — so the provider *cannot* claim a
 * working tunnel without Libbox (Phase 7). Nothing is faked.
 */
public class TunnelSession(
    private val engine: TunnelEngine,
    private val clock: Clock,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(ProviderState.IDLE)
    public val state: StateFlow<ProviderState> = _state
    private var profileId: String? = null
    private var since: Long? = null
    private var lastError: VpnError? = null
    private var cancelled = false

    /** Options are the `NETunnelProviderProtocol.providerConfiguration` dictionary values (strings only). */
    public data class StartRequest(val profileId: String, val tunnelConfiguration: TunnelConfiguration)

    /** Parsed provider configuration; throws typed errors, never leaks contents. */
    public fun parse(providerConfiguration: Map<String, Any?>?): StartRequest {
        val cfg = providerConfiguration ?: throw VpnError.InvalidTunnelConfiguration("providerConfiguration missing")
        val version = cfg[ProviderConfigKeys.SCHEMA_VERSION] as? String
        if (version != ProviderConfigKeys.CURRENT_SCHEMA) throw VpnError.InvalidTunnelConfiguration("schema ${version ?: "absent"} unsupported")
        val profileId = (cfg[ProviderConfigKeys.PROFILE_ID] as? String)?.takeIf { it.isNotBlank() }
            ?: throw VpnError.InvalidTunnelConfiguration("profileId missing")
        val tunnelJson = cfg[ProviderConfigKeys.TUNNEL_CONFIG] as? String
            ?: throw VpnError.InvalidTunnelConfiguration("tunnel configuration missing")
        return StartRequest(profileId, TunnelConfiguration.decode(tunnelJson).validated())
    }

    /**
     * startTunnel: IDLE/STOPPED/FAILED → STARTING → RUNNING, or → FAILED with a typed error.
     * [applySettings] performs `setTunnelNetworkSettings` and returns when the system accepted them.
     */
    public suspend fun start(request: StartRequest, applySettings: suspend (NetworkSettingsSpec) -> Unit): Result<Unit> {
        if (_state.value == ProviderState.STARTING || _state.value == ProviderState.RUNNING) {
            return Result.failure(VpnError.ProviderStartupFailure("already ${_state.value.name.lowercase()}"))
        }
        cancelled = false; lastError = null
        profileId = request.profileId
        _state.value = ProviderState.STARTING
        log("tunnel starting for profile ${request.profileId}")
        return try {
            val spec = NetworkSettingsSpec.from(request.tunnelConfiguration)
            applySettings(spec)
            if (cancelled) throw VpnError.ProviderStartupFailure("cancelled during settings")
            engine.start(request)
            if (cancelled) { engine.stop(); throw VpnError.ProviderStartupFailure("cancelled during engine start") }
            since = clock.nowMs()
            _state.value = ProviderState.RUNNING
            log("tunnel running")
            Result.success(Unit)
        } catch (t: Throwable) {
            val err = when (t) {
                is VpnError -> t
                is kotlinx.coroutines.CancellationException -> VpnError.ProviderStartupFailure("cancelled")
                else -> VpnError.ProviderStartupFailure(t::class.simpleName ?: "failure")
            }
            fail(err)
            runCatching { engine.stop() }
            Result.failure(err)
        }
    }

    /** stopTunnel(with:) — always ends in STOPPED; shutdown problems are reported, not swallowed. */
    public suspend fun stop(reason: StopReason): Result<Unit> {
        if (_state.value == ProviderState.STARTING) cancelled = true
        _state.value = ProviderState.STOPPING
        log("tunnel stopping: $reason")
        val r = runCatching { engine.stop() }
        _state.value = ProviderState.STOPPED
        since = null
        return r.fold({ Result.success(Unit) }, { Result.failure(VpnError.ProviderShutdownFailure(it::class.simpleName ?: "failure").also { e -> lastError = e }) })
    }

    /** Provider message handler; total: every request produces a response. */
    public suspend fun handle(bytes: ByteArray): ByteArray {
        val request = try { IpcCodec.decodeRequest(bytes) } catch (e: IpcCodec.DecodeException) {
            return IpcCodec.encodeResponse(IpcResponse.Error(VpnError.MalformedResponse("").code, e.message ?: "malformed"))
        }
        val response: IpcResponse = when (request) {
            IpcRequest.Status -> IpcResponse.Status(_state.value, profileId, since, lastError?.code)
            IpcRequest.Statistics -> engine.statistics()?.let { IpcResponse.Statistics(true, it.first, it.second) } ?: IpcResponse.Statistics(false)
            IpcRequest.Stop -> { stop(StopReason.APP_REQUEST); IpcResponse.Ack }
            is IpcRequest.Ping -> IpcResponse.Pong(request.nonce)
        }
        return IpcCodec.encodeResponse(response)
    }

    private fun fail(e: VpnError) { lastError = e; _state.value = ProviderState.FAILED; log("tunnel failed: ${e.code}") }

    public enum class StopReason { NONE, USER, PROVIDER_FAILED, NO_NETWORK, UNRECOVERABLE_NETWORK_CHANGE, PROVIDER_DISABLED,
        AUTHENTICATION_CANCELED, CONFIGURATION_FAILED, IDLE_TIMEOUT, CONFIGURATION_DISABLED, CONFIGURATION_REMOVED,
        SUPERCEDED, USER_LOGOUT, USER_SWITCH, CONNECTION_FAILED, SLEEP, APP_UPDATE, APP_REQUEST, UNKNOWN }
}

/** The engine slot that Libbox fills in Phase 7. */
public interface TunnelEngine {
    /** Must not return until packets can flow, or throw. */
    public suspend fun start(request: TunnelSession.StartRequest)
    public suspend fun stop()
    /** Provider process termination: release everything. Defaults to [stop]; must be idempotent. */
    public suspend fun terminate(): Unit = stop()
    /** (uplink, downlink) totals, or null if not available. */
    public fun statistics(): Pair<Long, Long>?

    /** No engine linked: start fails with a typed error. Prevents an "empty" tunnel from ever being reported as running. */
    public object None : TunnelEngine {
        override suspend fun start(request: TunnelSession.StartRequest): Unit =
            throw VpnError.ProviderStartupFailure("no tunnel engine linked (Libbox integration pending)")
        override suspend fun stop() {}
        override fun statistics(): Pair<Long, Long>? = null
    }
}

/** Keys of `NETunnelProviderProtocol.providerConfiguration`. Values are strings; never secrets. */
public object ProviderConfigKeys {
    public const val SCHEMA_VERSION: String = "ucc.schema"
    public const val CURRENT_SCHEMA: String = "1"
    public const val PROFILE_ID: String = "ucc.profileId"
    public const val TUNNEL_CONFIG: String = "ucc.tunnelConfig"

    public fun build(profileId: String, tunnel: TunnelConfiguration): Map<String, String> = mapOf(
        SCHEMA_VERSION to CURRENT_SCHEMA,
        PROFILE_ID to profileId,
        TUNNEL_CONFIG to tunnel.validated().encode(),
    )
}
