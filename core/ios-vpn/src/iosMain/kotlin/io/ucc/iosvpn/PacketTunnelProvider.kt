@file:OptIn(kotlin.experimental.ExperimentalObjCName::class, ExperimentalForeignApi::class, BetaInteropApi::class)

package io.ucc.iosvpn

import io.ucc.core.engine.manager.Clock
import io.ucc.iosinfra.toByteArray
import io.ucc.iosinfra.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSLog
import platform.NetworkExtension.NEPacketTunnelProvider
import platform.NetworkExtension.NEProviderStopReason
import platform.NetworkExtension.NETunnelProviderProtocol
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real `NEPacketTunnelProvider`. All decisions live in [TunnelSession]; this class
 * only translates Apple callbacks. Subclass it in the extension target as
 * `@objc(PacketTunnelProvider) class PacketTunnelProvider: UccPacketTunnelProvider {}`
 * (or set `NSExtensionPrincipalClass` to the Kotlin-exported name) and supply the
 * engine via [TunnelEngineFactory.install] (Kotlin/Native cannot subclass an Objective-C
 * class non-finally) — Phase 6 ships only [TunnelEngine.None].
 */
/**
 * Process-wide hook the extension binary uses to plug a real engine in (Phase 7: Libbox).
 * Must be installed before the system instantiates the provider (e.g. from a Swift
 * `@objc` load hook or the extension's principal-class initializer); unset → [TunnelEngine.None].
 */
public object TunnelEngineFactory {
    private var factory: (TunnelEngineContext) -> TunnelEngine = { TunnelEngine.None }
    public fun install(create: (TunnelEngineContext) -> TunnelEngine) { factory = create }
    public fun create(context: TunnelEngineContext): TunnelEngine = factory(context)
}

/** What an engine gets from the provider: the utun bridge (a [TunnelHost]) owned by this provider instance. */
public class TunnelEngineContext(public val tunnelHost: ExtensionTunnelHost)

@ObjCName("UccPacketTunnelProvider", exact = true)
public class UccPacketTunnelProvider : NEPacketTunnelProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = object : Clock { override fun nowMs(): Long = io.ucc.core.platform.currentTimeMillis() }

    private val tunnelHost: ExtensionTunnelHost by lazy { ExtensionTunnelHost(this) }
    private val engine: TunnelEngine by lazy { TunnelEngineFactory.create(TunnelEngineContext(tunnelHost)) }
    private val session: TunnelSession by lazy { TunnelSession(engine, clock) { NSLog("[ucc.tunnel] %s", it) } }

    override fun startTunnelWithOptions(options: Map<Any?, *>?, completionHandler: (NSError?) -> Unit) {
        val proto = protocolConfiguration as? NETunnelProviderProtocol
        @Suppress("UNCHECKED_CAST")
        val providerConfig = proto?.providerConfiguration as? Map<String, Any?>
        scope.launch {
            val result = runCatching { session.parse(providerConfig) }.fold(
                onSuccess = { req -> session.start(req) { spec -> applyNetworkSettings(spec) } },
                onFailure = { Result.failure(VpnError.classify(it)) },
            )
            completionHandler(result.exceptionOrNull()?.let { (it as? VpnError ?: VpnError.classify(it)).toNSError() })
        }
    }

    override fun stopTunnelWithReason(reason: NEProviderStopReason, completionHandler: () -> Unit) {
        scope.launch {
            tunnelHost.notifyRevoked()
            session.stop(reason.toStopReason())
            // The extension process ends after stopTunnel: release the core for good.
            runCatching { engine.terminate() }
            completionHandler()
            scope.cancel()
        }
    }

    override fun handleAppMessage(messageData: NSData, completionHandler: ((NSData?) -> Unit)?) {
        scope.launch {
            val reply = session.handle(messageData.toByteArray())
            completionHandler?.invoke(reply.toNSData())
        }
    }

    override fun sleepWithCompletionHandler(completionHandler: () -> Unit) { completionHandler() }
    override fun wake() {}

    private suspend fun applyNetworkSettings(spec: NetworkSettingsSpec) = suspendCancellableCoroutine<Unit> { cont ->
        setTunnelNetworkSettings(spec.toNetworkSettings()) { error ->
            if (error == null) cont.resume(Unit)
            else cont.resumeWithException(VpnError.ProviderStartupFailure("setTunnelNetworkSettings failed (${error.domain} ${error.code})"))
        }
    }

}

/** NSError domain used for every failure surfaced to the system by the provider. */
public const val UCC_TUNNEL_ERROR_DOMAIN: String = "io.ucc.iosvpn"

internal fun VpnError.toNSError(): NSError = NSError.errorWithDomain(
    UCC_TUNNEL_ERROR_DOMAIN,
    code.hashCode().toLong(),
    mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "$code: $detail"),
)

internal fun NEProviderStopReason.toStopReason(): TunnelSession.StopReason = when (this.toInt()) {
    0 -> TunnelSession.StopReason.NONE
    1 -> TunnelSession.StopReason.USER
    2 -> TunnelSession.StopReason.PROVIDER_FAILED
    3 -> TunnelSession.StopReason.NO_NETWORK
    4 -> TunnelSession.StopReason.UNRECOVERABLE_NETWORK_CHANGE
    5 -> TunnelSession.StopReason.PROVIDER_DISABLED
    6 -> TunnelSession.StopReason.AUTHENTICATION_CANCELED
    7 -> TunnelSession.StopReason.CONFIGURATION_FAILED
    8 -> TunnelSession.StopReason.IDLE_TIMEOUT
    9 -> TunnelSession.StopReason.CONFIGURATION_DISABLED
    10 -> TunnelSession.StopReason.CONFIGURATION_REMOVED
    11 -> TunnelSession.StopReason.SUPERCEDED
    12 -> TunnelSession.StopReason.USER_LOGOUT
    13 -> TunnelSession.StopReason.USER_SWITCH
    14 -> TunnelSession.StopReason.CONNECTION_FAILED
    15 -> TunnelSession.StopReason.SLEEP
    16 -> TunnelSession.StopReason.APP_UPDATE
    else -> TunnelSession.StopReason.UNKNOWN
}
