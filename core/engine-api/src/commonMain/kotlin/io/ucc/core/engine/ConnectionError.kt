package io.ucc.core.engine

/**
 * Classified failure. [userMessageKey] is a stable key the UI maps to a
 * localized string; [technicalDetail] is for logs and must already be
 * redacted by the producer (no passwords, keys, tokens).
 */
public sealed class ConnectionError(
    public val userMessageKey: String,
    public val technicalDetail: String,
    public val retryable: Boolean,
) {
    public class InvalidConfiguration(detail: String) :
        ConnectionError("error_invalid_configuration", detail, retryable = false)

    public class UnsupportedProtocol(detail: String) :
        ConnectionError("error_unsupported_protocol", detail, retryable = false)

    public class VpnPermissionDenied :
        ConnectionError("error_vpn_permission", "VpnService.prepare() returned an intent or establish() returned null", retryable = false)

    public class VpnRevoked :
        ConnectionError("error_vpn_revoked", "VpnService.onRevoke() — another VPN took over or user revoked", retryable = false)

    public class NetworkUnavailable :
        ConnectionError("error_network_unavailable", "no default network with INTERNET capability", retryable = true)

    public class DnsFailure(detail: String) :
        ConnectionError("error_dns", detail, retryable = true)

    public class ConnectionTimeout(detail: String) :
        ConnectionError("error_timeout", detail, retryable = true)

    public class AuthenticationFailure(detail: String) :
        ConnectionError("error_authentication", detail, retryable = false)

    public class TlsFailure(detail: String) :
        ConnectionError("error_tls", detail, retryable = false)

    public class CoreFailure(detail: String) :
        ConnectionError("error_core", detail, retryable = true)

    public class Unknown(detail: String) :
        ConnectionError("error_unknown", detail, retryable = true)

    override fun toString(): String = "${this::class.simpleName}($technicalDetail)"
}
