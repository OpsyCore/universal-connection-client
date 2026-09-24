package io.ucc.core.config.parser

import io.ucc.core.config.ConfigError
import io.ucc.core.platform.Base64Codec
import io.ucc.core.platform.UrlCodec

/**
 * Hand-rolled URI splitting: share links are frequently *not* RFC-3986 valid
 * (raw UTF-8 in fragments, unescaped `|` and `=` in query values, IPv6 without
 * brackets in some generators), so `java.net.URI` rejects too many real-world
 * links. This parser is permissive on input and strict on output.
 */
internal data class ShareUri(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val path: String,
    val query: Map<String, String>,
    val fragment: String?,
)

internal object UriTools {
    private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

    fun schemeOf(input: String): String? = schemeRegex.find(input.trim())?.groupValues?.get(1)?.lowercase()

    fun parse(input: String): ShareUri {
        val text = input.trim()
        val m = schemeRegex.find(text) ?: throw ParseException(ConfigError.MalformedUri("no scheme"))
        val scheme = m.groupValues[1].lowercase()
        var rest = text.substring(m.range.last + 1)

        var fragment: String? = null
        val hashIdx = rest.indexOf('#')
        if (hashIdx >= 0) {
            fragment = decode(rest.substring(hashIdx + 1))
            rest = rest.substring(0, hashIdx)
        }

        var query: Map<String, String> = emptyMap()
        val qIdx = rest.indexOf('?')
        if (qIdx >= 0) {
            query = parseQuery(rest.substring(qIdx + 1))
            rest = rest.substring(0, qIdx)
        }

        var path = ""
        val slashIdx = rest.indexOf('/')
        if (slashIdx >= 0) {
            path = decode(rest.substring(slashIdx))
            rest = rest.substring(0, slashIdx)
        }

        var userInfo: String? = null
        val atIdx = rest.lastIndexOf('@')
        if (atIdx >= 0) {
            userInfo = decode(rest.substring(0, atIdx))
            rest = rest.substring(atIdx + 1)
        }

        val (host, port) = splitHostPort(rest)
        if (host.isEmpty()) throw ParseException(ConfigError.MissingField("host"))
        return ShareUri(scheme, userInfo, host, port, path, query, fragment)
    }

    fun splitHostPort(authority: String): Pair<String, Int?> {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) throw ParseException(ConfigError.MalformedUri("unterminated IPv6 literal"))
            val host = authority.substring(1, end)
            val portPart = authority.substring(end + 1)
            val port = if (portPart.startsWith(":")) parsePort(portPart.substring(1)) else null
            return host to port
        }
        val colons = authority.count { it == ':' }
        if (colons > 1) {
            // bare IPv6 without brackets, maybe with trailing :port — ambiguous; treat last segment as port only if the remainder parses as IPv6
            val idx = authority.lastIndexOf(':')
            val maybeHost = authority.substring(0, idx)
            val maybePort = authority.substring(idx + 1)
            return if (maybePort.all { it.isDigit() } && maybeHost.count { it == ':' } >= 2) maybeHost to parsePort(maybePort) else authority to null
        }
        val idx = authority.lastIndexOf(':')
        if (idx < 0) return authority to null
        return authority.substring(0, idx) to parsePort(authority.substring(idx + 1))
    }

    fun parsePort(text: String): Int {
        val p = text.toIntOrNull() ?: throw ParseException(ConfigError.InvalidField("port", "not a number: '$text'"))
        if (p !in 1..65535) throw ParseException(ConfigError.InvalidField("port", "out of range: $p"))
        return p
    }

    fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            val v = if (eq >= 0) decode(pair.substring(eq + 1)) else ""
            if (k.isNotEmpty() && !out.containsKey(k)) out[k] = v
        }
        return out
    }

    fun decode(s: String): String = try {
        // A form decoder would turn '+' into space, which is wrong for base64 fragments and passwords; UrlCodec keeps it.
        UrlCodec.decode(s)
    } catch (e: IllegalArgumentException) {
        s
    }

    /** Lenient base64 (standard or URL-safe, padding optional). Returns null if not decodable. */
    fun base64Lenient(s: String): ByteArray? {
        val cleaned = s.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val normalized = cleaned.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            Base64Codec.decodeStrict(padded)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun isValidUuid(s: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(s)
}

internal class ParseException(val error: ConfigError) : Exception(error.detail)
