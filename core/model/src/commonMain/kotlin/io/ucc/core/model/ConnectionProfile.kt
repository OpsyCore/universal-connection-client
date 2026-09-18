package io.ucc.core.model

import kotlinx.serialization.Serializable

/**
 * The normalized internal representation of one connectable endpoint.
 *
 * Every other layer (UI, connection manager, smart engine, core adapters)
 * works with this type — never with raw share links or core-specific JSON.
 *
 * Identity is [id] (a random UUID assigned at import). [name] is display-only
 * and may collide; never use it as a key.
 */
@Serializable
public data class ConnectionProfile(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val transport: Transport = Transport.None,
    val tls: TlsSettings = TlsSettings(),
    val authentication: Authentication = Authentication.None,
    val routing: ProfileRouting = ProfileRouting(),
    val dns: ProfileDns = ProfileDns(),
    val metadata: ProfileMetadata = ProfileMetadata(),
    /**
     * Escape hatch for options that have no normalized field yet. Keys are
     * namespaced by core (`singbox.*`, `xray.*`). The config generator of the
     * matching core merges them last, so they can override generated values.
     */
    val coreSpecificOptions: Map<String, String> = emptyMap(),
) {
    /** Content fingerprint that ignores user metadata; used for duplicate detection. */
    public val fingerprint: String
        get() = ProfileFingerprint.of(this)
}

/** Per-profile routing overrides (global rules live in settings). */
@Serializable
public data class ProfileRouting(
    /** Force all traffic through this outbound regardless of global rules. */
    val bypassGlobalRules: Boolean = false,
)

/** Per-profile DNS overrides. */
@Serializable
public data class ProfileDns(
    /** Remote DNS used while this profile is active; null = global setting. */
    val remoteDns: String? = null,
)

@Serializable
public data class ProfileMetadata(
    /** Origin of the profile; drives update/merge behaviour. */
    val source: ProfileSource = ProfileSource.Manual,
    val groupId: String? = null,
    val favorite: Boolean = false,
    val userRenamed: Boolean = false,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val lastUsedAtEpochMs: Long? = null,
    /** Free-form tags coming from the subscription (country, plan, …). */
    val tags: List<String> = emptyList(),
    /** Original share link / JSON kept for export. Secrets inside are covered by profile encryption. */
    val rawSource: String? = null,
)

@Serializable
public sealed class ProfileSource {
    @Serializable
    public data object Manual : ProfileSource()

    @Serializable
    public data object Clipboard : ProfileSource()

    @Serializable
    public data object QrCode : ProfileSource()

    @Serializable
    public data class File(val fileName: String) : ProfileSource()

    @Serializable
    public data class Subscription(val subscriptionId: String) : ProfileSource()
}
