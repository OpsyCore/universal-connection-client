package io.ucc.core.config.parser

import io.ucc.core.config.ConfigError
import io.ucc.core.config.IdGenerator
import io.ucc.core.config.ParseResult
import io.ucc.core.config.TimeSource
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileMetadata
import io.ucc.core.model.ProfileSource
import io.ucc.core.model.Protocol

/** One parser per URI scheme family. */
internal interface SchemeParser {
    val schemes: Set<String>
    fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink
}

internal data class ParseContext(val ids: IdGenerator, val time: TimeSource, val source: ProfileSource)

internal data class ParsedLink(val profile: ConnectionProfile, val warnings: List<String> = emptyList())

/**
 * Entry point for single share links (`vless://…`, `vmess://…`, …).
 * Never throws: every failure becomes [ParseResult.Failure] with a classified error.
 */
public class LinkParser(
    private val ids: IdGenerator = IdGenerator.Random,
    private val time: TimeSource = TimeSource.System,
) {
    private val parsers: List<SchemeParser> = listOf(
        VlessParser, VmessParser, TrojanParser, ShadowsocksParser,
        Hysteria2Parser, HysteriaParser, TuicParser, WireGuardParser, SocksHttpParser,
    )
    private val byScheme: Map<String, SchemeParser> = parsers.flatMap { p -> p.schemes.map { it to p } }.toMap()

    public fun supportedSchemes(): Set<String> = byScheme.keys

    public fun canParse(input: String): Boolean = UriTools.schemeOf(input)?.let { it in byScheme } == true

    public fun parse(input: String, source: ProfileSource = ProfileSource.Manual): ParseResult {
        val raw = input.trim()
        val scheme = UriTools.schemeOf(raw) ?: return ParseResult.Failure(ConfigError.MalformedUri("input is not a URI"))
        val parser = byScheme[scheme] ?: return ParseResult.Failure(ConfigError.UnknownScheme("scheme '$scheme' is not supported"))
        val ctx = ParseContext(ids, time, source)
        return try {
            val parsed = if (parser is VmessParser) {
                // vmess:// has two shapes (base64 JSON or URI); it handles URI splitting itself.
                parser.parseRaw(raw, ctx)
            } else {
                parser.parse(UriTools.parse(raw), raw, ctx)
            }
            ParseResult.Success(parsed.profile, parsed.warnings)
        } catch (e: ParseException) {
            ParseResult.Failure(e.error)
        } catch (e: Exception) {
            ParseResult.Failure(ConfigError.MalformedUri("${e.javaClass.simpleName}: ${e.message}"))
        }
    }
}

internal fun ParseContext.metadata(raw: String): ProfileMetadata = ProfileMetadata(
    source = source,
    createdAtEpochMs = time.nowMs(),
    updatedAtEpochMs = time.nowMs(),
    rawSource = raw,
)

internal fun defaultName(protocol: Protocol, host: String, port: Int?): String =
    "${protocol.name.lowercase()} $host" + (port?.let { ":$it" } ?: "")

internal fun ShareUri.requirePort(): Int = port ?: throw ParseException(ConfigError.MissingField("port"))

internal fun Map<String, String>.opt(vararg keys: String): String? {
    for (k in keys) this[k]?.takeIf { it.isNotEmpty() }?.let { return it }
    return null
}

internal fun String?.asBool(): Boolean = this != null && (this == "1" || this.equals("true", true))
