package io.ucc.core.vpn

/**
 * Decides whether the boot receiver may start the tunnel. Pure and JVM-tested; the receiver
 * only gathers the inputs. Every condition is a hard requirement:
 *
 * - the user switched "auto-connect on boot" ON (default OFF);
 * - Android still holds this app's VPN consent (`VpnService.prepare(ctx) == null`) — the
 *   receiver can never show the consent dialog, so without it nothing happens;
 * - there is a profile to connect (the user's selected one, else the last active one);
 * - the tunnel is not already active (e.g. always-on VPN or a START_STICKY restart beat us).
 */
public object BootConnectPolicy {
    public sealed class Decision {
        public data class Connect(val profileId: String) : Decision()
        public data class Skip(val reason: String) : Decision()
    }

    public fun decide(
        enabled: Boolean,
        vpnConsentGranted: Boolean,
        selectedProfileId: String?,
        lastActiveProfileId: String?,
        alreadyActive: Boolean,
    ): Decision {
        if (!enabled) return Decision.Skip("switch off")
        if (!vpnConsentGranted) return Decision.Skip("no VPN consent")
        if (alreadyActive) return Decision.Skip("already active")
        val id = selectedProfileId?.takeIf { it.isNotBlank() } ?: lastActiveProfileId?.takeIf { it.isNotBlank() }
        return if (id == null) Decision.Skip("no profile") else Decision.Connect(id)
    }

    /** The two boot actions we accept; anything else is ignored. */
    public val ACCEPTED_ACTIONS: Set<String> = setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED")
}
