package io.ucc.core.config.parser

import io.ucc.core.config.ConfigError
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport

private fun quicTls(q: Map<String, String>, host: String): TlsSettings = TlsSettings(
    enabled = true,
    serverName = q.opt("sni", "peer") ?: host.takeIf { !it.looksLikeIp() },
    allowInsecure = q.opt("insecure", "allowInsecure", "allowinsecure", "skip-cert-verify").asBool(),
    alpn = q.opt("alpn")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
    certificatePem = null,
)

/** `hysteria2://password@host:port/?obfs=salamander&obfs-password=…&sni=…&insecure=1#name` (also `hy2://`). */
internal object Hysteria2Parser : SchemeParser {
    override val schemes = setOf("hysteria2", "hy2")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val password = uri.userInfo ?: ""
        if (password.isEmpty()) warnings += "hysteria2 link has no password (server may allow anonymous)"
        val port = uri.port ?: 443
        val q = uri.query
        val obfs = q.opt("obfs")?.takeIf { it != "none" }
        if (obfs != null && obfs != "salamander") throw ParseException(ConfigError.Unsupported("hysteria2 obfs '$obfs'"))
        if (obfs != null && q.opt("obfs-password") == null) throw ParseException(ConfigError.MissingField("obfs-password"))
        val ports = q.opt("mport", "ports")
        q.opt("pinSHA256")?.let { warnings += "pinSHA256 certificate pinning is not supported yet and was ignored" }
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.HYSTERIA2, uri.host, port),
                protocol = Protocol.HYSTERIA2,
                address = uri.host,
                port = port,
                transport = Transport.None,
                tls = quicTls(q, uri.host),
                authentication = Authentication.Hysteria2(
                    password = password, obfsType = obfs, obfsPassword = q.opt("obfs-password"),
                    upMbps = q.opt("upmbps", "up")?.mbps(), downMbps = q.opt("downmbps", "down")?.mbps(), ports = ports,
                ),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/** Hysteria v1: `hysteria://host:port?protocol=udp&auth=…&peer=…&insecure=1&upmbps=…&downmbps=…&obfs=…#name`. */
internal object HysteriaParser : SchemeParser {
    override val schemes = setOf("hysteria", "hy")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val port = uri.requirePort()
        val q = uri.query
        val protocol = q.opt("protocol") ?: "udp"
        if (protocol != "udp") throw ParseException(ConfigError.Unsupported("hysteria protocol '$protocol' (only udp is supported)"))
        val up = q.opt("upmbps")?.mbps() ?: run { warnings += "upmbps missing; defaulting to 10"; 10 }
        val down = q.opt("downmbps")?.mbps() ?: run { warnings += "downmbps missing; defaulting to 50"; 50 }
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.HYSTERIA, uri.host, port),
                protocol = Protocol.HYSTERIA,
                address = uri.host,
                port = port,
                transport = Transport.None,
                tls = quicTls(q, uri.host),
                authentication = Authentication.Hysteria(
                    auth = q.opt("auth"), upMbps = up, downMbps = down, obfs = q.opt("obfs", "obfsParam"), protocol = protocol,
                ),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/** `tuic://uuid:password@host:port?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=…#name`. */
internal object TuicParser : SchemeParser {
    override val schemes = setOf("tuic")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val ui = uri.userInfo ?: throw ParseException(ConfigError.MissingField("uuid:password"))
        val colon = ui.indexOf(':')
        if (colon < 0) throw ParseException(ConfigError.MalformedUri("tuic userinfo must be uuid:password"))
        val uuid = ui.substring(0, colon)
        val password = ui.substring(colon + 1)
        if (!UriTools.isValidUuid(uuid)) throw ParseException(ConfigError.InvalidField("uuid", "not a UUID"))
        val port = uri.requirePort()
        val q = uri.query
        val cc = q.opt("congestion_control", "congestion-control") ?: "cubic"
        if (cc !in setOf("cubic", "new_reno", "bbr")) throw ParseException(ConfigError.InvalidField("congestion_control", cc))
        val relay = q.opt("udp_relay_mode", "udp-relay-mode") ?: "native"
        if (relay !in setOf("native", "quic")) throw ParseException(ConfigError.InvalidField("udp_relay_mode", relay))
        if (q.opt("version") == "4") throw ParseException(ConfigError.Unsupported("TUIC v4 (only v5 is supported)"))
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.TUIC, uri.host, port),
                protocol = Protocol.TUIC,
                address = uri.host,
                port = port,
                transport = Transport.None,
                tls = quicTls(q, uri.host).let { if (it.alpn.isEmpty()) it.copy(alpn = listOf("h3")) else it },
                authentication = Authentication.Tuic(
                    uuid = uuid, password = password, congestionControl = cc, udpRelayMode = relay,
                    zeroRttHandshake = q.opt("reduce_rtt", "zero_rtt_handshake").asBool(),
                    heartbeatMs = q.opt("heartbeat")?.toIntOrNull(),
                ),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/**
 * WireGuard share links have no formal standard; we accept the widely used
 * `wireguard://privateKey@host:port?publickey=…&address=10.0.0.2/32,fd00::2/128&presharedkey=…&reserved=1,2,3&mtu=1280#name`
 * (also `wg://`). Full `.conf` files are handled by the file importer (Phase 2b).
 */
internal object WireGuardParser : SchemeParser {
    override val schemes = setOf("wireguard", "wg")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val privateKey = uri.userInfo?.takeIf { it.isNotBlank() } ?: throw ParseException(ConfigError.MissingField("private key"))
        val port = uri.requirePort()
        val q = uri.query
        val publicKey = q.opt("publickey", "publicKey", "peer_public_key", "pbk") ?: throw ParseException(ConfigError.MissingField("publickey"))
        val addresses = (q.opt("address", "ip") ?: throw ParseException(ConfigError.MissingField("address")))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.contains('/')) it else if (it.contains(':')) "$it/128" else "$it/32" }
        for (k in listOf(privateKey, publicKey)) {
            if (UriTools.base64Lenient(k)?.size != 32) throw ParseException(ConfigError.InvalidField("key", "WireGuard keys must be 32 bytes base64"))
        }
        val reserved = q.opt("reserved")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        if (reserved.isNotEmpty() && reserved.size != 3) throw ParseException(ConfigError.InvalidField("reserved", "must be exactly 3 bytes"))
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.WIREGUARD, uri.host, port),
                protocol = Protocol.WIREGUARD,
                address = uri.host,
                port = port,
                transport = Transport.None,
                tls = TlsSettings(enabled = false),
                authentication = Authentication.WireGuard(
                    privateKey = privateKey, peerPublicKey = publicKey, preSharedKey = q.opt("presharedkey", "preSharedKey", "psk"),
                    localAddresses = addresses, reserved = reserved, mtu = q.opt("mtu")?.toIntOrNull() ?: 1408,
                ),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/** `socks://user:pass@host:port#name`, `socks5://`, `http://`, `https://` (HTTP CONNECT over TLS). */
internal object SocksHttpParser : SchemeParser {
    override val schemes = setOf("socks", "socks5", "socks5h", "http", "https")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val isSocks = uri.scheme.startsWith("socks")
        val protocol = if (isSocks) Protocol.SOCKS else Protocol.HTTP
        val port = uri.port ?: when (uri.scheme) { "http" -> 80; "https" -> 443; else -> 1080 }
        var user: String? = null
        var pass: String? = null
        uri.userInfo?.let { ui ->
            // Some clients base64-encode "user:pass" (NekoBox/v2rayN); accept both.
            val plain = if (ui.contains(':')) ui else UriTools.base64Lenient(ui)?.decodeToString()?.takeIf { it.contains(':') } ?: ui
            val c = plain.indexOf(':')
            if (c >= 0) { user = plain.substring(0, c); pass = plain.substring(c + 1) } else user = plain
        }
        val tls = if (uri.scheme == "https" || uri.query.opt("tls").asBool() || uri.query.opt("security") == "tls") {
            TlsSettings(enabled = true, serverName = uri.query.opt("sni") ?: uri.host.takeIf { !it.looksLikeIp() }, allowInsecure = uri.query.opt("allowInsecure", "insecure").asBool())
        } else TlsSettings(enabled = false)
        if (isSocks && tls.enabled) warnings += "TLS over SOCKS is not supported; TLS flag ignored"
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(protocol, uri.host, port),
                protocol = protocol,
                address = uri.host,
                port = port,
                transport = Transport.Tcp,
                tls = if (isSocks) TlsSettings(enabled = false) else tls,
                authentication = if (user == null && pass == null) Authentication.None else Authentication.UserPassword(user, pass),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

private fun String.mbps(): Int? = trim().lowercase().removeSuffix("mbps").removeSuffix("m").trim().toIntOrNull()
