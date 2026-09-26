package io.ucc.applogic

import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Transport
import io.ucc.core.model.redactHost

/**
 * Human-readable, **secret-free** projection of a profile for the confirm
 * screen. Nothing marked @Secret in the model is ever placed here; fields
 * derived from secrets (e.g. "password set") are booleans.
 */
data class ProfilePreview(
    val id: String,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val transport: String,
    val security: String,
    val details: List<Pair<String, String>>,
    val hasCredentials: Boolean,
    val insecureTls: Boolean,
) {
    companion object {
        fun of(p: ConnectionProfile): ProfilePreview {
            val t = p.transport
            val transport = when (t) {
                Transport.Tcp -> "TCP"
                is Transport.WebSocket -> "WebSocket ${t.path}" + (t.host?.let { " · $it" } ?: "")
                is Transport.Grpc -> "gRPC" + (t.serviceName.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                is Transport.HttpUpgradeOrH2 -> (if (t.upgrade) "HTTPUpgrade " else "HTTP/2 ") + t.path
                is Transport.Unsupported -> t.name.uppercase()
                Transport.None -> when (p.protocol.scheme) { "hysteria", "hysteria2", "tuic" -> "QUIC"; "wireguard" -> "UDP"; else -> "—" }
            }
            val security = when {
                p.tls.reality != null -> "REALITY" + (p.tls.serverName?.let { " · $it" } ?: "")
                p.tls.enabled -> "TLS" + (p.tls.serverName?.let { " · $it" } ?: "") + (p.tls.fingerprint?.let { " · $it" } ?: "")
                else -> "none"
            }
            val details = ArrayList<Pair<String, String>>()
            when (val a = p.authentication) {
                is Authentication.Vless -> { a.flow?.let { details += "flow" to it } }
                is Authentication.Vmess -> { details += "security" to a.security; if (a.alterId != 0) details += "alterId" to a.alterId.toString() }
                is Authentication.Shadowsocks -> { details += "method" to a.method; a.plugin?.let { details += "plugin" to it } }
                is Authentication.Hysteria2 -> { a.obfsType?.let { details += "obfs" to it }; a.ports?.let { details += "ports" to it } }
                is Authentication.Hysteria -> { details += "up/down" to "${a.upMbps ?: "?"}/${a.downMbps ?: "?"} Mbps" }
                is Authentication.Tuic -> { details += "congestion" to a.congestionControl; details += "udp relay" to a.udpRelayMode }
                is Authentication.WireGuard -> { details += "addresses" to a.localAddresses.joinToString(", "); details += "mtu" to a.mtu.toString() }
                is Authentication.UserPassword -> { a.username?.let { details += "user" to it } }
                is Authentication.Trojan, Authentication.None -> {}
            }
            if (p.tls.alpn.isNotEmpty()) details += "alpn" to p.tls.alpn.joinToString(",")
            return ProfilePreview(
                id = p.id,
                name = p.name,
                protocol = p.protocol.name,
                server = p.address,
                port = p.port,
                transport = transport,
                security = security,
                details = details,
                hasCredentials = p.authentication !is Authentication.None,
                insecureTls = p.tls.enabled && p.tls.allowInsecure,
            )
        }

        /** For logs only. */
        fun logLine(p: ConnectionProfile): String = "${p.protocol.scheme}://${redactHost(p.address)}:${p.port}"
    }
}
