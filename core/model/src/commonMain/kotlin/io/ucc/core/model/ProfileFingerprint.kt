package io.ucc.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ucc.core.platform.sha256
import io.ucc.core.platform.toHexLower

/**
 * Deterministic content hash over the *connection-relevant* parts of a profile.
 *
 * Two profiles with the same fingerprint reach the same server with the same
 * credentials and transport; they differ only in name, metadata or the user's
 * per-profile overrides ([ProfileRouting], [ProfileDns]), which are edits that
 * must survive a subscription merge and therefore cannot be part of identity.
 * Used for duplicate detection at import and for subscription merge.
 */
public object ProfileFingerprint {
    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
    }

    public fun of(profile: ConnectionProfile): String {
        val canonical = profile.copy(
            id = "",
            name = "",
            metadata = ProfileMetadata(),
            routing = ProfileRouting(),
            dns = ProfileDns(),
            address = profile.address.trim().lowercase(),
        )
        val bytes = json.encodeToString(canonical).encodeToByteArray()
        return sha256(bytes).toHexLower()
    }
}
