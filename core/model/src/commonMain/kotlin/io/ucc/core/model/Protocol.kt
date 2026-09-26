package io.ucc.core.model

import kotlinx.serialization.Serializable

/**
 * Proxy protocols the internal model can describe.
 *
 * Whether a protocol is *connectable* depends on the active core; see
 * `CoreCapabilities` in `core/engine-api`. The model deliberately describes
 * more than any single core supports so that unsupported profiles can still be
 * imported, stored and shown with an "unsupported by current core" reason.
 */
@Serializable
public enum class Protocol(public val scheme: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("ss"),
    HYSTERIA("hysteria"),
    HYSTERIA2("hysteria2"),
    TUIC("tuic"),
    WIREGUARD("wireguard"),
    SOCKS("socks"),
    HTTP("http"),
    ;

    public companion object {
        public fun fromScheme(scheme: String): Protocol? {
            val s = scheme.lowercase()
            return entries.firstOrNull { it.scheme == s } ?: when (s) {
                "hy2" -> HYSTERIA2
                "hy", "hysteria1" -> HYSTERIA
                "socks5", "socks4", "socks4a" -> SOCKS
                "https" -> HTTP
                "wg" -> WIREGUARD
                else -> null
            }
        }
    }
}
