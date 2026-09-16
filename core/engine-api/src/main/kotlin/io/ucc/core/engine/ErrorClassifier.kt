package io.ucc.core.engine

/**
 * Turns free-form core/OS failure text into a [ConnectionError]. Pure and
 * core-agnostic so adapters share one vocabulary and it can be unit-tested
 * on the JVM. Callers pass an already-redacted message; this function does a
 * second best-effort redaction so a missed secret never reaches logs.
 */
public object ErrorClassifier {

    public fun classifyStart(message: String?, corePrefix: String = "core"): ConnectionError {
        val raw = message.orEmpty()
        val m = raw.lowercase()
        val detail = "$corePrefix start: ${redact(raw)}"
        return when {
            "permission" in m || "not prepared" in m || "revoked" in m -> ConnectionError.VpnPermissionDenied()
            "certificate" in m || "x509" in m || "tls handshake" in m || "handshake failure" in m || "bad record mac" in m ->
                ConnectionError.TlsFailure(detail)
            "auth" in m && ("fail" in m || "reject" in m || "invalid" in m) -> ConnectionError.AuthenticationFailure(detail)
            "parse config" in m || "decode config" in m || "unknown field" in m || "invalid" in m || "missing" in m ->
                ConnectionError.InvalidConfiguration(detail)
            "network is unreachable" in m || "no route to host" in m -> ConnectionError.NetworkUnavailable()
            "no such host" in m || "dns" in m && "fail" in m || "lookup" in m && "fail" in m -> ConnectionError.DnsFailure(detail)
            "timeout" in m || "timed out" in m || "deadline exceeded" in m -> ConnectionError.ConnectionTimeout(detail)
            "connection refused" in m || "connection reset" in m -> ConnectionError.ConnectionTimeout(detail)
            else -> ConnectionError.CoreFailure(detail)
        }
    }

    private val secretPattern = Regex(
        "(?i)(password|passwd|uuid|private_key|privatekey|pre_shared_key|auth_str|auth|psk|token|secret)(\"?\\s*[:=]\\s*\"?)[^\\s\",}]+",
    )

    /** Scrubs `key=value` / `"key": "value"` pairs whose key looks credential-like. */
    public fun redact(text: String): String = text.replace(secretPattern, "$1$2***").replace(uuidPattern, "<uuid>")

    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
