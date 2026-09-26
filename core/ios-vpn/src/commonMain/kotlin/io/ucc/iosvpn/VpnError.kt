package io.ucc.iosvpn

import io.ucc.core.engine.ConnectionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Typed failures of the iOS VPN layer. Messages never carry server addresses,
 * credentials or configuration payloads — only stable, non-sensitive detail.
 */
public sealed class VpnError(public val detail: String) : Exception(detail) {
    /** No saved configuration, user declined the VPN permission prompt, or preferences cannot be loaded/saved. */
    public class PermissionOrConfigurationUnavailable(detail: String) : VpnError(detail)
    /** The tunnel provider session does not exist / is not running, so it cannot be started or messaged. */
    public class ProviderUnavailable(detail: String) : VpnError(detail)
    /** The provider received a configuration it cannot use (missing/invalid fields, wrong schema version). */
    public class InvalidTunnelConfiguration(detail: String) : VpnError(detail)
    public class IpcTimeout(public val timeoutMs: Long) : VpnError("no response within ${timeoutMs}ms")
    public class IpcCancelled : VpnError("cancelled")
    public class IpcTransport(detail: String) : VpnError(detail)
    public class MalformedResponse(detail: String) : VpnError(detail)
    public class ProviderStartupFailure(detail: String) : VpnError(detail)
    public class ProviderShutdownFailure(detail: String) : VpnError(detail)

    /** Stable code for logs, IPC and tests. */
    public val code: String get() = this::class.simpleName ?: "VpnError"

    override fun toString(): String = "$code($detail)"

    /** Bridge to the shared error family the ConnectionManager/UI already understands. */
    public fun toConnectionError(): ConnectionError = when (this) {
        is PermissionOrConfigurationUnavailable -> ConnectionError.VpnPermissionDenied()
        is InvalidTunnelConfiguration -> ConnectionError.InvalidConfiguration(detail)
        is IpcTimeout -> ConnectionError.ConnectionTimeout(detail)
        is ProviderUnavailable, is IpcCancelled, is IpcTransport, is MalformedResponse,
        is ProviderStartupFailure, is ProviderShutdownFailure -> ConnectionError.CoreFailure("$code: $detail")
    }

    public companion object {
        /** Deterministic classification of anything thrown around the IPC/VPN boundary. */
        public fun classify(t: Throwable, timeoutMs: Long = 0): VpnError = when (t) {
            is VpnError -> t
            is TimeoutCancellationException -> IpcTimeout(timeoutMs)
            is CancellationException -> IpcCancelled()
            is IpcCodec.DecodeException -> MalformedResponse(t.message ?: "undecodable")
            else -> IpcTransport(t::class.simpleName ?: "failure")
        }
    }
}
