package io.ucc.core.model

import kotlinx.serialization.Serializable

/** TLS layer configuration. `enabled = false` means plaintext. */
@Serializable
public data class TlsSettings(
    val enabled: Boolean = false,
    val serverName: String? = null,
    /**
     * Skips certificate verification. Security-sensitive: the config engine
     * records it faithfully but the UI must surface a warning and it is never
     * enabled implicitly.
     */
    val allowInsecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    /** uTLS fingerprint name (e.g. "chrome", "firefox", "random"). */
    val fingerprint: String? = null,
    val reality: Reality? = null,
    /** Pinned certificate (PEM) if the link carries one. */
    val certificatePem: String? = null,
    /** Disables SNI entirely. */
    val disableSni: Boolean = false,
) {
    @Serializable
    public data class Reality(
        val publicKey: String,
        val shortId: String = "",
        val spiderX: String? = null,
    )
}
