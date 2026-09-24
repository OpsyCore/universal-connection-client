package io.ucc.core.config

import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Transport

/**
 * Decides whether the *selected* core can carry a profile, using only the
 * engine-agnostic [CoreCapabilities] contract. UI must consult this instead of
 * hard-coding protocol/transport assumptions.
 */
public class CapabilityCheck(private val capabilities: CoreCapabilities) {

    /** Null when supported; otherwise a stable, secret-free reason. */
    public fun unsupportedReason(profile: ConnectionProfile): UnsupportedReason? {
        if (profile.protocol !in capabilities.protocols) {
            return UnsupportedReason.Protocol(profile.protocol.scheme)
        }
        val transportName = transportName(profile.transport)
        if (transportName != null && transportName !in capabilities.transports) {
            return UnsupportedReason.Transport(transportName)
        }
        if (profile.tls.reality != null && !capabilities.reality) {
            return UnsupportedReason.Feature("reality")
        }
        return null
    }

    public fun isSupported(profile: ConnectionProfile): Boolean = unsupportedReason(profile) == null

    public companion object {
        /** Canonical transport names used in [CoreCapabilities.transports]. Null = protocol has no stream transport. */
        public fun transportName(t: Transport): String? = when (t) {
            Transport.Tcp -> "tcp"
            is Transport.WebSocket -> "ws"
            is Transport.Grpc -> "grpc"
            is Transport.HttpUpgradeOrH2 -> if (t.upgrade) "httpupgrade" else "http"
            is Transport.Unsupported -> t.name
            Transport.None -> null
        }
    }
}

public sealed class UnsupportedReason(public val messageKey: String, public val detail: String) {
    public class Protocol(scheme: String) : UnsupportedReason("unsupported_protocol", scheme)
    public class Transport(name: String) : UnsupportedReason("unsupported_transport", name)
    public class Feature(name: String) : UnsupportedReason("unsupported_feature", name)
}
