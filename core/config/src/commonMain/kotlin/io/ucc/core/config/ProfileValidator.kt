package io.ucc.core.config

import io.ucc.core.model.ConnectionProfile

/**
 * Last line of defence after parsing/normalisation: structural checks that
 * every persisted profile must satisfy regardless of where it came from.
 * Returns problems as classified errors; never includes secrets.
 */
public object ProfileValidator {
    private val hostRegex = Regex("^[A-Za-z0-9._:\\-\\[\\]]+$")

    public fun validate(profile: ConnectionProfile): List<ConfigError> {
        val errors = ArrayList<ConfigError>(2)
        if (profile.address.isBlank()) errors += ConfigError.MissingField("host")
        else if (!hostRegex.matches(profile.address) || profile.address.any { it.isWhitespace() }) {
            errors += ConfigError.InvalidField("host", "contains illegal characters")
        }
        if (profile.port !in 1..65535) errors += ConfigError.InvalidField("port", "out of range: ${profile.port}")
        if (profile.name.isBlank()) errors += ConfigError.MissingField("name")
        if (profile.tls.enabled && profile.tls.reality != null && profile.tls.reality!!.publicKey.isBlank()) {
            errors += ConfigError.MissingField("reality public key")
        }
        return errors
    }

    public fun isValid(profile: ConnectionProfile): Boolean = validate(profile).isEmpty()
}
