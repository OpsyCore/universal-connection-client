package io.ucc.core.config.parser

import io.ucc.core.config.ConfigError
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** `vless://uuid@host:port?type=…&security=…#name` */
internal object VlessParser : SchemeParser {
    override val schemes = setOf("vless")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: throw ParseException(ConfigError.MissingField("uuid"))
        if (!UriTools.isValidUuid(uuid)) warnings += "uuid is not RFC-4122 formatted (Xray maps custom strings; sing-box requires a real UUID)"
        val port = uri.requirePort()
        val q = uri.query
        val transport = CommonTransport.transport(q, warnings)
        val tls = CommonTransport.tls(q, uri.host, defaultEnabled = false, warnings)
        val flow = q.opt("flow")?.takeIf { it.isNotEmpty() }
        if (flow != null && flow != "xtls-rprx-vision" && flow != "xtls-rprx-vision-udp443") {
            throw ParseException(ConfigError.Unsupported("VLESS flow '$flow' is not supported by any maintained core"))
        }
        if (flow != null && transport !is Transport.Tcp) warnings += "flow is only meaningful with TCP transport"
        val enc = q.opt("encryption") ?: "none"
        if (enc != "none") throw ParseException(ConfigError.Unsupported("VLESS encryption '$enc' is not supported (Xray post-quantum encryption is not available in sing-box)"))
        CommonTransport.warnUnknown(q, warnings)
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.VLESS, uri.host, port),
                protocol = Protocol.VLESS,
                address = uri.host,
                port = port,
                transport = transport,
                tls = tls,
                authentication = Authentication.Vless(uuid = uuid, flow = flow, encryption = enc, packetEncoding = q.opt("packetEncoding", "packetencoding")),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/** `trojan://password@host:port?…#name` — TLS is on by default. */
internal object TrojanParser : SchemeParser {
    override val schemes = setOf("trojan", "trojan-go")

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: throw ParseException(ConfigError.MissingField("password"))
        val port = uri.requirePort()
        val transport = CommonTransport.transport(uri.query, warnings)
        val tls = CommonTransport.tls(uri.query, uri.host, defaultEnabled = true, warnings)
        CommonTransport.warnUnknown(uri.query, warnings)
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.TROJAN, uri.host, port),
                protocol = Protocol.TROJAN,
                address = uri.host,
                port = port,
                transport = transport,
                tls = tls,
                authentication = Authentication.Trojan(password),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/**
 * `vmess://<base64 JSON>` (v2rayN "v":"2" format) or the newer
 * `vmess://uuid@host:port?…` URI form.
 */
internal object VmessParser : SchemeParser {
    override val schemes = setOf("vmess")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink = parseUriForm(uri, raw, ctx)

    fun parseRaw(raw: String, ctx: ParseContext): ParsedLink {
        val body = raw.substring("vmess://".length)
        val hashIdx = body.indexOf('#')
        val payload = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val payloadNoQuery = payload.substringBefore('?')
        val decoded = if (!payloadNoQuery.contains('@')) UriTools.base64Lenient(payloadNoQuery)?.toString(Charsets.UTF_8) else null
        return if (decoded != null && decoded.trimStart().startsWith("{")) {
            parseJsonForm(decoded, raw, ctx)
        } else {
            parseUriForm(UriTools.parse(raw), raw, ctx)
        }
    }

    private fun parseJsonForm(text: String, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject ?: throw ParseException(ConfigError.InvalidJson("vmess payload is not a JSON object"))
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ParseException(ConfigError.InvalidJson("vmess payload: ${e.message}"))
        }
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val host = str("add") ?: throw ParseException(ConfigError.MissingField("add"))
        val port = UriTools.parsePort(str("port") ?: throw ParseException(ConfigError.MissingField("port")))
        val uuid = str("id") ?: throw ParseException(ConfigError.MissingField("id"))
        val aid = str("aid")?.toIntOrNull() ?: 0
        if (aid != 0) warnings += "alterId=$aid (legacy VMess MD5 auth) is deprecated; sing-box always uses AEAD, alterId is ignored"
        val net = str("net") ?: "tcp"
        // Map JSON fields onto the same query-shaped map so transport/TLS decoding is shared.
        val q = buildMap {
            put("type", net)
            str("host")?.let { put("host", it) }
            str("path")?.let { put("path", it) }
            if (net == "grpc") str("path")?.let { put("serviceName", it) }
            str("type")?.let { put("headerType", it) }
            str("tls")?.let { put("security", it) }
            str("sni")?.let { put("sni", it) }
            str("fp")?.let { put("fp", it) }
            str("alpn")?.let { put("alpn", it) }
            str("mode")?.let { put("mode", it) }
            if (obj["skip-cert-verify"]?.jsonPrimitive?.contentOrNull.asBool() || str("allowInsecure").asBool()) put("allowInsecure", "1")
        }
        val transport = CommonTransport.transport(q, warnings)
        val tls = CommonTransport.tls(q, host, defaultEnabled = false, warnings)
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = str("ps") ?: defaultName(Protocol.VMESS, host, port),
                protocol = Protocol.VMESS,
                address = host,
                port = port,
                transport = transport,
                tls = tls,
                authentication = Authentication.Vmess(uuid = uuid, alterId = aid, security = str("scy") ?: "auto"),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }

    private fun parseUriForm(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() } ?: throw ParseException(ConfigError.MissingField("uuid"))
        val port = uri.requirePort()
        val transport = CommonTransport.transport(uri.query, warnings)
        val tls = CommonTransport.tls(uri.query, uri.host, defaultEnabled = false, warnings)
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.VMESS, uri.host, port),
                protocol = Protocol.VMESS,
                address = uri.host,
                port = port,
                transport = transport,
                tls = tls,
                authentication = Authentication.Vmess(uuid = uuid, security = uri.query.opt("encryption", "security_method") ?: "auto"),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}

/**
 * Shadowsocks: SIP002 `ss://base64(method:password)@host:port/?plugin=…#name`,
 * SIP002 with plain userinfo `ss://method:password@…`, and the legacy
 * `ss://base64(method:password@host:port)#name`.
 */
internal object ShadowsocksParser : SchemeParser {
    override val schemes = setOf("ss")
    private val SUPPORTED_METHODS = setOf(
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "none", "plain",
        // legacy stream ciphers (sing-box still supports them behind a warning)
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "rc4-md5", "chacha20-ietf", "xchacha20",
    )

    override fun parse(uri: ShareUri, raw: String, ctx: ParseContext): ParsedLink {
        val warnings = mutableListOf<String>()
        var host = uri.host
        var port = uri.port
        val userInfo: String
        val query: Map<String, String>
        if (uri.userInfo == null) {
            // legacy: whole authority is base64
            val decoded = UriTools.base64Lenient(uri.host)?.toString(Charsets.UTF_8)
                ?: throw ParseException(ConfigError.MalformedUri("ss link is neither SIP002 nor base64 legacy form"))
            val at = decoded.lastIndexOf('@')
            if (at < 0) throw ParseException(ConfigError.MalformedUri("legacy ss payload lacks '@'"))
            userInfo = decoded.substring(0, at)
            val (h, p) = UriTools.splitHostPort(decoded.substring(at + 1))
            host = h; port = p
            query = uri.query
        } else {
            userInfo = uri.userInfo
            query = uri.query
        }
        val credentials = if (userInfo.contains(':')) userInfo else {
            UriTools.base64Lenient(userInfo)?.toString(Charsets.UTF_8)
                ?: throw ParseException(ConfigError.MalformedUri("ss userinfo is not base64"))
        }
        val colon = credentials.indexOf(':')
        if (colon <= 0) throw ParseException(ConfigError.MalformedUri("ss userinfo must be method:password"))
        val method = credentials.substring(0, colon).lowercase()
        val password = credentials.substring(colon + 1)
        if (method !in SUPPORTED_METHODS) throw ParseException(ConfigError.Unsupported("shadowsocks method '$method'"))
        if (method.contains("cfb") || method.contains("ctr") || method == "rc4-md5" || method == "chacha20-ietf" || method == "xchacha20") {
            warnings += "'$method' is an insecure legacy cipher"
        }
        val finalPort = port ?: throw ParseException(ConfigError.MissingField("port"))
        var plugin: String? = null
        var pluginOpts: String? = null
        query.opt("plugin")?.let { p ->
            val semi = p.indexOf(';')
            plugin = if (semi >= 0) p.substring(0, semi) else p
            pluginOpts = if (semi >= 0) p.substring(semi + 1) else null
            if (plugin == "simple-obfs") plugin = "obfs-local"
            if (plugin !in setOf("obfs-local", "v2ray-plugin")) throw ParseException(ConfigError.Unsupported("shadowsocks plugin '$plugin'"))
        }
        return ParsedLink(
            ConnectionProfile(
                id = ctx.ids.next(),
                name = uri.fragment?.takeIf { it.isNotBlank() } ?: defaultName(Protocol.SHADOWSOCKS, host, finalPort),
                protocol = Protocol.SHADOWSOCKS,
                address = host,
                port = finalPort,
                transport = Transport.Tcp,
                tls = TlsSettings(enabled = false),
                authentication = Authentication.Shadowsocks(
                    method = method, password = password, plugin = plugin, pluginOptions = pluginOpts,
                    udpOverTcp = query.opt("uot", "udp-over-tcp").asBool(),
                ),
                metadata = ctx.metadata(raw),
            ),
            warnings,
        )
    }
}
