package io.ucc.core.config

import io.ucc.core.model.ConnectionProfile

/** Outcome of parsing one piece of input. Never throws to callers. */
public sealed class ParseResult {
    public data class Success(
        val profile: ConnectionProfile,
        /** Non-fatal observations (e.g. "unknown query parameter x ignored"). */
        val warnings: List<String> = emptyList(),
    ) : ParseResult()

    public data class Failure(val error: ConfigError) : ParseResult()
}

/**
 * Result of importing arbitrary text that may contain many links, a JSON
 * document, or a base64 subscription body.
 */
public data class ImportReport(
    val profiles: List<ConnectionProfile>,
    val failures: List<ImportFailure>,
    val format: InputFormat,
) {
    val isEmpty: Boolean get() = profiles.isEmpty() && failures.isEmpty()
}

public data class ImportFailure(
    /** The offending fragment, truncated and with credentials removed. */
    val snippet: String,
    val error: ConfigError,
)

public enum class InputFormat { SHARE_LINKS, BASE64_SHARE_LINKS, SING_BOX_JSON, UNKNOWN, EMPTY }

/** Classified, user-explainable configuration error. `messageKey` is a stable localisation key. */
public sealed class ConfigError(public val messageKey: String, public val detail: String) {
    public class UnknownScheme(detail: String) : ConfigError("config_error_unknown_scheme", detail)
    public class MalformedUri(detail: String) : ConfigError("config_error_malformed", detail)
    public class MissingField(field: String) : ConfigError("config_error_missing_field", "missing required field: $field")
    public class InvalidField(field: String, why: String) : ConfigError("config_error_invalid_field", "$field: $why")
    public class InvalidJson(detail: String) : ConfigError("config_error_invalid_json", detail)
    public class Unsupported(detail: String) : ConfigError("config_error_unsupported", detail)

    override fun toString(): String = "${this::class.simpleName}($detail)"
}
