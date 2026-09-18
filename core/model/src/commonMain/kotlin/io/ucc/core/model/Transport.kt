package io.ucc.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stream transport carried under the protocol (V2Ray-family "network"). */
@Serializable
public sealed class Transport {
    @Serializable
    @SerialName("tcp")
    public data object Tcp : Transport()

    @Serializable
    @SerialName("ws")
    public data class WebSocket(
        val path: String = "/",
        val host: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** V2Ray "early data" (`ed` query / `?ed=2048`). */
        val maxEarlyData: Int? = null,
        val earlyDataHeaderName: String? = null,
    ) : Transport()

    @Serializable
    @SerialName("grpc")
    public data class Grpc(
        val serviceName: String = "",
        /** "multi" mode is an Xray extension; sing-box treats every call as a stream. */
        val multiMode: Boolean = false,
    ) : Transport()

    @Serializable
    @SerialName("http")
    public data class HttpUpgradeOrH2(
        val path: String = "/",
        val host: List<String> = emptyList(),
        /** `http` = HTTP/2 transport; `httpupgrade` = plain HTTP/1.1 upgrade. */
        val upgrade: Boolean = false,
        val headers: Map<String, String> = emptyMap(),
    ) : Transport()

    /**
     * Transports the model can *describe* but which the currently selected core
     * cannot carry (e.g. Xray XHTTP, mKCP). Kept so import never silently loses
     * information and validation can explain why the profile is not connectable.
     */
    @Serializable
    @SerialName("unsupported")
    public data class Unsupported(val name: String, val rawOptions: Map<String, String> = emptyMap()) : Transport()

    /** Protocols with their own framing (Hysteria, TUIC, WireGuard, SOCKS, HTTP). */
    @Serializable
    @SerialName("none")
    public data object None : Transport()
}
