package io.ucc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol-specific credentials. Every field that is a secret is marked with
 * [Secret] so that persistence encrypts it and logging redacts it.
 */
@Serializable
public sealed class Authentication {
    @Serializable
    @SerialName("vless")
    public data class Vless(
        @Secret val uuid: String,
        /** e.g. "xtls-rprx-vision". */
        val flow: String? = null,
        val encryption: String = "none",
        /** Packet encoding hint: "xudp" or "packetaddr". */
        val packetEncoding: String? = null,
    ) : Authentication()

    @Serializable
    @SerialName("vmess")
    public data class Vmess(
        @Secret val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",
        val packetEncoding: String? = null,
    ) : Authentication()

    @Serializable
    @SerialName("trojan")
    public data class Trojan(@Secret val password: String) : Authentication()

    @Serializable
    @SerialName("shadowsocks")
    public data class Shadowsocks(
        val method: String,
        @Secret val password: String,
        /** SIP003 plugin name (e.g. "obfs-local", "v2ray-plugin"). */
        val plugin: String? = null,
        val pluginOptions: String? = null,
        /** Shadowsocks UDP-over-TCP (`uot`) request. */
        val udpOverTcp: Boolean = false,
    ) : Authentication()

    @Serializable
    @SerialName("hysteria")
    public data class Hysteria(
        @Secret val auth: String? = null,
        val upMbps: Int? = null,
        val downMbps: Int? = null,
        val obfs: String? = null,
        val protocol: String? = null,
    ) : Authentication()

    @Serializable
    @SerialName("hysteria2")
    public data class Hysteria2(
        @Secret val password: String,
        val obfsType: String? = null,
        @Secret val obfsPassword: String? = null,
        val upMbps: Int? = null,
        val downMbps: Int? = null,
        /** Port hopping range, e.g. "20000-30000". */
        val ports: String? = null,
    ) : Authentication()

    @Serializable
    @SerialName("tuic")
    public data class Tuic(
        @Secret val uuid: String,
        @Secret val password: String,
        val congestionControl: String = "cubic",
        val udpRelayMode: String = "native",
        val zeroRttHandshake: Boolean = false,
        val heartbeatMs: Int? = null,
    ) : Authentication()

    @Serializable
    @SerialName("wireguard")
    public data class WireGuard(
        @Secret val privateKey: String,
        val peerPublicKey: String,
        @Secret val preSharedKey: String? = null,
        val localAddresses: List<String>,
        val reserved: List<Int> = emptyList(),
        val mtu: Int = 1408,
    ) : Authentication()

    @Serializable
    @SerialName("userpass")
    public data class UserPassword(
        val username: String? = null,
        @Secret val password: String? = null,
    ) : Authentication()

    @Serializable
    @SerialName("none")
    public data object None : Authentication()
}

/** Marks a property as a secret: encrypted at rest, redacted in logs and exports without credentials. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Secret
