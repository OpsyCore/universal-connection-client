package io.ucc.core.config.export

import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ucc.core.platform.Base64Codec
import io.ucc.core.platform.UrlCodec

/**
 * Serialises a normalised profile back into a standard share link. Output is
 * the *current* profile (including user edits such as name), not the original
 * `rawSource`; the round-trip `parse(export(p))` is fingerprint-stable, which
 * is covered by tests.
 *
 * The result **contains credentials** — callers must treat it as a secret
 * (share sheet / clipboard only on explicit user action, never logged).
 */
public object ShareLinkExporter {

    /** Returns null for profiles that have no link form (e.g. unsupported transport with lost options). */
    public fun export(p: ConnectionProfile): String? = when (val a = p.authentication) {
        is Authentication.Vless -> uri("vless", a.uuid, p, buildQuery(p) { put("encryption", a.encryption); a.flow?.let { put("flow", it) }; a.packetEncoding?.let { put("packetEncoding", it) } })
        is Authentication.Vmess ->
            // v2rayN JSON form is what every client understands; REALITY has no JSON field, so fall back to URI form for it.
            if (p.tls.reality == null && a.packetEncoding == null) vmessJson(p, a)
            else uri("vmess", a.uuid, p, buildQuery(p) { put("encryption", a.security); a.packetEncoding?.let { put("packetEncoding", it) } })
        is Authentication.Trojan -> uri("trojan", a.password, p, buildQuery(p) {})
        is Authentication.Shadowsocks -> shadowsocks(p, a)
        is Authentication.Hysteria2 -> hysteria2(p, a)
        is Authentication.Hysteria -> hysteria(p, a)
        is Authentication.Tuic -> tuic(p, a)
        is Authentication.WireGuard -> wireguard(p, a)
        is Authentication.UserPassword -> socksHttp(p, a.username, a.password)
        Authentication.None -> if (p.protocol == Protocol.SOCKS || p.protocol == Protocol.HTTP) socksHttp(p, null, null) else null
    }

    /** Multi-profile export: one link per line, the format every client accepts as a "subscription body". */
    public fun exportAll(profiles: List<ConnectionProfile>): String =
        profiles.mapNotNull(::export).joinToString("\n")

    /** Same list, base64-encoded (classic subscription body). */
    public fun exportAllBase64(profiles: List<ConnectionProfile>): String =
        Base64Codec.encode(exportAll(profiles).encodeToByteArray())

    // ---- protocol families -------------------------------------------------

    private inline fun buildQuery(p: ConnectionProfile, extra: LinkedHashMap<String, String>.() -> Unit): LinkedHashMap<String, String> {
        val q = LinkedHashMap<String, String>()
        q.extra()
        transportParams(p.transport, q)
        tlsParams(p.tls, q)
        return q
    }

    private fun transportParams(t: Transport, q: MutableMap<String, String>) {
        when (t) {
            Transport.Tcp, Transport.None -> q["type"] = "tcp"
            is Transport.WebSocket -> {
                q["type"] = "ws"; q["path"] = t.path
                t.host?.let { q["host"] = it }
                t.maxEarlyData?.let { q["ed"] = it.toString() }
                t.earlyDataHeaderName?.takeIf { it != "Sec-WebSocket-Protocol" }?.let { q["eh"] = it }
            }
            is Transport.Grpc -> { q["type"] = "grpc"; if (t.serviceName.isNotEmpty()) q["serviceName"] = t.serviceName; if (t.multiMode) q["mode"] = "multi" }
            is Transport.HttpUpgradeOrH2 -> {
                q["type"] = if (t.upgrade) "httpupgrade" else "http"; q["path"] = t.path
                if (t.host.isNotEmpty()) q["host"] = t.host.joinToString(",")
            }
            is Transport.Unsupported -> { q["type"] = t.name; t.rawOptions.forEach { (k, v) -> q.putIfAbsent(k, v) } }
        }
    }

    private fun tlsParams(tls: TlsSettings, q: MutableMap<String, String>) {
        if (!tls.enabled) { q["security"] = "none"; return }
        q["security"] = if (tls.reality != null) "reality" else "tls"
        tls.serverName?.let { q["sni"] = it }
        tls.fingerprint?.let { q["fp"] = it }
        if (tls.alpn.isNotEmpty()) q["alpn"] = tls.alpn.joinToString(",")
        if (tls.allowInsecure) q["allowInsecure"] = "1"
        tls.reality?.let { r -> q["pbk"] = r.publicKey; if (r.shortId.isNotEmpty()) q["sid"] = r.shortId; r.spiderX?.let { q["spx"] = it } }
    }

    private fun vmessJson(p: ConnectionProfile, a: Authentication.Vmess): String {
        val t = p.transport
        val obj = buildJsonObject {
            put("v", "2")
            put("ps", p.name)
            put("add", p.address)
            put("port", p.port.toString())
            put("id", a.uuid)
            put("aid", a.alterId.toString())
            put("scy", a.security)
            when (t) {
                Transport.Tcp, Transport.None -> { put("net", "tcp"); put("type", "none") }
                is Transport.WebSocket -> { put("net", "ws"); put("type", "none"); put("path", t.path + (t.maxEarlyData?.let { "?ed=$it" } ?: "")); t.host?.let { put("host", it) } }
                is Transport.Grpc -> { put("net", "grpc"); put("type", "none"); put("path", t.serviceName); if (t.multiMode) put("mode", "multi") }
                is Transport.HttpUpgradeOrH2 -> { put("net", if (t.upgrade) "httpupgrade" else "http"); put("type", "none"); put("path", t.path); if (t.host.isNotEmpty()) put("host", t.host.joinToString(",")) }
                is Transport.Unsupported -> { put("net", t.name); put("type", "none"); t.rawOptions["path"]?.let { put("path", it) }; t.rawOptions["host"]?.let { put("host", it) } }
            }
            put("tls", if (p.tls.enabled) "tls" else "")
            if (p.tls.enabled) {
                p.tls.serverName?.let { put("sni", it) }
                p.tls.fingerprint?.let { put("fp", it) }
                if (p.tls.alpn.isNotEmpty()) put("alpn", p.tls.alpn.joinToString(","))
                if (p.tls.allowInsecure) put("allowInsecure", "1")
            }
        }
        return "vmess://" + Base64Codec.encode(obj.toString().encodeToByteArray())
    }

    private fun shadowsocks(p: ConnectionProfile, a: Authentication.Shadowsocks): String {
        val userInfo = Base64Codec.encodeUrlSafeNoPadding("${a.method}:${a.password}".encodeToByteArray())
        val q = LinkedHashMap<String, String>()
        a.plugin?.let { q["plugin"] = it + (a.pluginOptions?.let { o -> ";$o" } ?: "") }
        if (a.udpOverTcp) q["uot"] = "1"
        return assemble("ss", userInfo, p.address, p.port, q, p.name, rawUserInfo = true)
    }

    private fun hysteria2(p: ConnectionProfile, a: Authentication.Hysteria2): String {
        val q = LinkedHashMap<String, String>()
        quicTls(p.tls, q)
        a.obfsType?.let { q["obfs"] = it; a.obfsPassword?.let { pw -> q["obfs-password"] = pw } }
        a.upMbps?.let { q["upmbps"] = it.toString() }
        a.downMbps?.let { q["downmbps"] = it.toString() }
        a.ports?.let { q["mport"] = it }
        return assemble("hysteria2", a.password, p.address, p.port, q, p.name)
    }

    private fun hysteria(p: ConnectionProfile, a: Authentication.Hysteria): String {
        val q = LinkedHashMap<String, String>()
        q["protocol"] = a.protocol ?: "udp"
        a.auth?.let { q["auth"] = it }
        quicTls(p.tls, q, sniKey = "peer")
        a.upMbps?.let { q["upmbps"] = it.toString() }
        a.downMbps?.let { q["downmbps"] = it.toString() }
        a.obfs?.let { q["obfs"] = it }
        return assemble("hysteria", null, p.address, p.port, q, p.name)
    }

    private fun tuic(p: ConnectionProfile, a: Authentication.Tuic): String {
        val q = LinkedHashMap<String, String>()
        q["congestion_control"] = a.congestionControl
        q["udp_relay_mode"] = a.udpRelayMode
        quicTls(p.tls, q)
        if (a.zeroRttHandshake) q["reduce_rtt"] = "1"
        a.heartbeatMs?.let { q["heartbeat"] = it.toString() }
        return assemble("tuic", "${a.uuid}:${a.password}", p.address, p.port, q, p.name)
    }

    private fun quicTls(tls: TlsSettings, q: MutableMap<String, String>, sniKey: String = "sni") {
        tls.serverName?.let { q[sniKey] = it }
        if (tls.allowInsecure) q["insecure"] = "1"
        if (tls.alpn.isNotEmpty()) q["alpn"] = tls.alpn.joinToString(",")
    }

    private fun wireguard(p: ConnectionProfile, a: Authentication.WireGuard): String {
        val q = LinkedHashMap<String, String>()
        q["publickey"] = a.peerPublicKey
        q["address"] = a.localAddresses.joinToString(",")
        a.preSharedKey?.let { q["presharedkey"] = it }
        if (a.reserved.isNotEmpty()) q["reserved"] = a.reserved.joinToString(",")
        q["mtu"] = a.mtu.toString()
        return assemble("wireguard", a.privateKey, p.address, p.port, q, p.name)
    }

    private fun socksHttp(p: ConnectionProfile, user: String?, pass: String?): String {
        val scheme = when {
            p.protocol == Protocol.SOCKS -> "socks5"
            p.tls.enabled -> "https"
            else -> "http"
        }
        val q = LinkedHashMap<String, String>()
        if (scheme == "https") { p.tls.serverName?.let { q["sni"] = it }; if (p.tls.allowInsecure) q["allowInsecure"] = "1" }
        val ui = when {
            user == null && pass == null -> null
            pass == null -> user
            else -> "${user.orEmpty()}:$pass"
        }
        return assemble(scheme, ui, p.address, p.port, q, p.name, encodeUserInfoParts = true)
    }

    private fun uri(scheme: String, userInfo: String, p: ConnectionProfile, q: Map<String, String>): String =
        assemble(scheme, userInfo, p.address, p.port, q, p.name)

    // ---- assembly ----------------------------------------------------------

    private fun assemble(
        scheme: String,
        userInfo: String?,
        host: String,
        port: Int,
        query: Map<String, String>,
        name: String,
        rawUserInfo: Boolean = false,
        encodeUserInfoParts: Boolean = false,
    ): String {
        val sb = StringBuilder(scheme).append("://")
        if (userInfo != null) {
            val ui = when {
                rawUserInfo -> userInfo
                encodeUserInfoParts -> userInfo.split(':', limit = 2).joinToString(":") { enc(it) }
                else -> enc(userInfo)
            }
            sb.append(ui).append('@')
        }
        sb.append(if (host.contains(':') && !host.startsWith("[")) "[$host]" else host)
        sb.append(':').append(port)
        if (query.isNotEmpty()) sb.append('?').append(query.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" })
        if (name.isNotBlank()) sb.append('#').append(enc(name))
        return sb.toString()
    }

    /** RFC-3986 percent-encoding (space → %20, `~` kept); see [UrlCodec.encode]. */
    private fun enc(s: String): String = UrlCodec.encode(s)
}
