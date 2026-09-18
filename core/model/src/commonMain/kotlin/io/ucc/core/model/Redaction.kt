package io.ucc.core.model

/**
 * Produces a log-safe description of a profile: protocol, host, port and
 * transport only. Never includes credentials, keys or the raw link.
 */
public fun ConnectionProfile.toLogString(): String =
    "${protocol.name.lowercase()}://${redactHost(address)}:$port/${transport.typeName()}" +
        (if (tls.enabled) "+tls" else "") +
        (if (tls.reality != null) "+reality" else "")

private fun Transport.typeName(): String = when (this) {
    Transport.Tcp -> "tcp"
    is Transport.WebSocket -> "ws"
    is Transport.Grpc -> "grpc"
    is Transport.HttpUpgradeOrH2 -> if (upgrade) "httpupgrade" else "http"
    is Transport.Unsupported -> "unsupported($name)"
    Transport.None -> "-"
}

/** Keeps enough of the host to correlate log lines without leaking the full endpoint. */
public fun redactHost(host: String): String {
    if (host.length <= 6) return "***"
    return host.take(3) + "***" + host.takeLast(3)
}
