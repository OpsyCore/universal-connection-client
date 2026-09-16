package io.ucc.core.config

import io.ucc.core.config.parser.LinkParser
import io.ucc.core.config.parser.SingBoxJsonImporter
import io.ucc.core.config.parser.UriTools
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileSource
import java.util.Base64

/**
 * Turns arbitrary pasted/scanned/downloaded text into profiles.
 *
 * Detection order (deterministic):
 * 1. Empty → [InputFormat.EMPTY]
 * 2. Starts with `{` → sing-box JSON (outbounds are extracted)
 * 3. Any line starts with a supported scheme → share links, one per line
 *    (also handles whitespace/`|`-separated links on a single line)
 * 4. Whole body decodes as base64 to text containing links → subscription body
 * 5. Otherwise [InputFormat.UNKNOWN]
 *
 * Deduplication by [ConnectionProfile.fingerprint] happens *inside one import*
 * only; merging with the existing store is the store's responsibility.
 */
public class ConfigImporter(
    private val links: LinkParser = LinkParser(),
    private val json: SingBoxJsonImporter = SingBoxJsonImporter(),
) {
    public fun import(text: String, source: ProfileSource = ProfileSource.Manual): ImportReport {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportReport(emptyList(), emptyList(), InputFormat.EMPTY)

        if (trimmed.startsWith("{")) {
            return json.import(trimmed, source).copy(format = InputFormat.SING_BOX_JSON)
        }

        val direct = extractLinks(trimmed)
        if (direct.isNotEmpty()) return parseAll(direct, source, InputFormat.SHARE_LINKS)

        val decoded = decodeBase64Text(trimmed)
        if (decoded != null) {
            if (decoded.trimStart().startsWith("{")) return json.import(decoded, source).copy(format = InputFormat.SING_BOX_JSON)
            val inner = extractLinks(decoded)
            if (inner.isNotEmpty()) return parseAll(inner, source, InputFormat.BASE64_SHARE_LINKS)
        }

        return ImportReport(
            emptyList(),
            listOf(ImportFailure(snippet(trimmed), ConfigError.UnknownScheme("no recognised share link, JSON or subscription content"))),
            InputFormat.UNKNOWN,
        )
    }

    private fun parseAll(rawLinks: List<String>, source: ProfileSource, format: InputFormat): ImportReport {
        val ok = ArrayList<ConnectionProfile>()
        val bad = ArrayList<ImportFailure>()
        val seen = HashSet<String>()
        for (raw in rawLinks) {
            when (val r = links.parse(raw, source)) {
                is ParseResult.Success -> if (seen.add(r.profile.fingerprint)) ok += r.profile
                is ParseResult.Failure -> bad += ImportFailure(snippet(raw), r.error)
            }
        }
        return ImportReport(ok, bad, format)
    }

    /** Finds share links in free text: line-separated, or separated by whitespace / `|` on one line. */
    internal fun extractLinks(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) continue
            val parts = l.split(Regex("[\\s|]+")).filter { it.isNotEmpty() }
            if (parts.size > 1 && parts.all { UriTools.schemeOf(it) != null }) {
                // Links glued together on one line
                out += parts
            } else if (UriTools.schemeOf(l) != null) {
                // Any URI-looking line is passed on; unsupported schemes become classified failures.
                out += l
            }
        }
        return out
    }

    private fun decodeBase64Text(text: String): String? {
        val cleaned = text.replace(Regex("\\s"), "")
        if (cleaned.length < 8 || !Regex("^[A-Za-z0-9+/=_-]+$").matches(cleaned)) return null
        val normalized = cleaned.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = try { Base64.getDecoder().decode(padded) } catch (e: IllegalArgumentException) { return null }
        val s = String(bytes, Charsets.UTF_8)
        // Reject binary garbage: must be mostly printable.
        val printable = s.count { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
        return if (printable >= s.length * 0.95) s else null
    }

    private fun snippet(raw: String): String {
        // Drop userinfo (credentials) and cap length before this reaches UI/logs.
        val noCreds = raw.replace(Regex("://[^@/\\s]+@"), "://***@")
        return if (noCreds.length > 80) noCreds.take(77) + "…" else noCreds
    }
}
