package io.ucc.core.config.parser

import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport

/**
 * Shared decoding of the V2Ray-family query parameters used by VLESS, Trojan
 * and VMess (URI form):
 *   type/net, security, sni, fp, alpn, allowInsecure/insecure, pbk, sid, spx,
 *   path, host, serviceName, mode, headerType, ed, eh
 */
internal object CommonTransport {
    private val KNOWN = setOf(
        "type", "net", "security", "tls", "sni", "peer", "fp", "alpn", "allowinsecure", "insecure", "skip-cert-verify",
        "pbk", "sid", "spx", "path", "host", "servicename", "mode", "headertype", "ed", "eh", "encryption", "flow",
        "packetencoding", "seed", "obfs", "obfsparam", "protoparam", "remarks", "plugin", "group",
    )

    fun transport(q: Map<String, String>, warnings: MutableList<String>): Transport {
        val type = (q.opt("type", "net") ?: "tcp").lowercase()
        return when (type) {
            "tcp", "raw" -> {
                val header = q.opt("headerType", "headertype")
                if (header != null && header != "none") {
                    warnings += "tcp header type '$header' is not supported and was ignored"
                }
                Transport.Tcp
            }
            "ws" -> {
                val ed = q.opt("ed")?.toIntOrNull()
                // Some generators embed ?ed=2048 inside the path; normalize that too.
                var path = q.opt("path") ?: "/"
                var earlyData = ed
                val edInPath = Regex("[?&]ed=(\\d+)").find(path)
                if (edInPath != null) {
                    earlyData = edInPath.groupValues[1].toInt()
                    path = path.replace(edInPath.value, "").trimEnd('?', '&')
                }
                Transport.WebSocket(
                    path = path.ifEmpty { "/" },
                    host = q.opt("host"),
                    maxEarlyData = earlyData,
                    earlyDataHeaderName = if (earlyData != null) (q.opt("eh") ?: "Sec-WebSocket-Protocol") else null,
                )
            }
            "grpc", "gun" -> Transport.Grpc(
                serviceName = q.opt("serviceName", "servicename", "path") ?: "",
                multiMode = q.opt("mode") == "multi",
            )
            "http", "h2" -> Transport.HttpUpgradeOrH2(
                path = q.opt("path") ?: "/",
                host = q.opt("host")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                upgrade = false,
            )
            "httpupgrade" -> Transport.HttpUpgradeOrH2(
                path = q.opt("path") ?: "/",
                host = listOfNotNull(q.opt("host")),
                upgrade = true,
            )
            else -> Transport.Unsupported(type, q.filterKeys { it.lowercase() !in setOf("security", "sni", "fp", "alpn", "pbk", "sid") })
        }
    }

    fun tls(q: Map<String, String>, host: String, defaultEnabled: Boolean, warnings: MutableList<String>): TlsSettings {
        val security = q.opt("security")?.lowercase() ?: if (q.opt("tls").asBool()) "tls" else null
        val enabled = when (security) {
            null -> defaultEnabled
            "none", "" -> false
            "tls", "reality", "xtls" -> true
            else -> { warnings += "unknown security '$security' treated as tls"; true }
        }
        if (!enabled) return TlsSettings(enabled = false)
        val sni = q.opt("sni", "peer")
        val reality = if (security == "reality") {
            val pbk = q.opt("pbk") ?: throw ParseException(io.ucc.core.config.ConfigError.MissingField("pbk (REALITY public key)"))
            TlsSettings.Reality(publicKey = pbk, shortId = q.opt("sid") ?: "", spiderX = q.opt("spx"))
        } else null
        val insecure = q.opt("allowInsecure", "allowinsecure", "insecure", "skip-cert-verify").asBool()
        return TlsSettings(
            enabled = true,
            serverName = sni ?: host.takeIf { !it.looksLikeIp() },
            allowInsecure = insecure,
            alpn = q.opt("alpn")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            fingerprint = q.opt("fp"),
            reality = reality,
        )
    }

    fun warnUnknown(q: Map<String, String>, warnings: MutableList<String>) {
        q.keys.filter { it.lowercase() !in KNOWN }.forEach { warnings += "unknown parameter '$it' ignored" }
    }
}

internal fun String.looksLikeIp(): Boolean =
    Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(this) || (contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(this))
