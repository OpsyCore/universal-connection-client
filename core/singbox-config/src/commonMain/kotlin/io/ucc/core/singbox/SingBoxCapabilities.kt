package io.ucc.core.singbox

import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.Transport

/** What the pinned sing-box build (see docs/CORE_DECISION.md) can actually carry. */
public object SingBoxCapabilities {
    public const val PINNED_TAG: String = "v1.13.21"

    public val capabilities: CoreCapabilities = CoreCapabilities(
        protocols = setOf(
            Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS,
            Protocol.HYSTERIA, Protocol.HYSTERIA2, Protocol.TUIC, Protocol.WIREGUARD,
            Protocol.SOCKS, Protocol.HTTP,
        ),
        transports = setOf("tcp", "ws", "grpc", "http", "httpupgrade"),
        reality = true,
        utlsFingerprints = true,
        perAppRouting = true,
        ruleSets = true,
        fakeIp = true,
        hotReload = true,
    )

    /**
     * Returns null when the profile can be converted to a working sing-box
     * outbound, otherwise a technical reason (stable English; the UI maps it).
     */
    public fun unsupportedReason(profile: ConnectionProfile): String? {
        if (profile.protocol !in capabilities.protocols) {
            return "protocol ${profile.protocol.scheme} is not supported by sing-box $PINNED_TAG"
        }
        val t = profile.transport
        if (t is Transport.Unsupported) {
            return "transport '${t.name}' is not supported by sing-box $PINNED_TAG"
        }
        if (t is Transport.Grpc && t.multiMode) {
            // sing-box ignores multi mode; connection still works, so not fatal.
            return null
        }
        return null
    }
}
