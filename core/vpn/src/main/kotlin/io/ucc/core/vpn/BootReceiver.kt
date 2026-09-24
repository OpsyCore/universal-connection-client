package io.ucc.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Optional auto-connect after reboot (Settings → VPN → "Connect on boot", default OFF).
 *
 * Android rules honoured:
 * - `RECEIVE_BOOT_COMPLETED` is already in the merged manifest (androidx.work); this receiver is
 *   declared `exported="false"` with the two boot actions only.
 * - Starting a foreground service from a BOOT_COMPLETED receiver is an explicit exemption from
 *   the Android 12+ background-start restriction; the connect goes through the regular
 *   [io.ucc.core.engine.manager.ConnectionManager] → [AndroidTunnelHost] →
 *   `startForegroundService` path, and [UcVpnService.onStartCommand] calls `startForeground()`
 *   immediately (well within the 5 s window). Nothing new is invented here.
 * - VPN consent cannot be requested from a receiver; if `VpnService.prepare()` is non-null
 *   the receiver does nothing (see [BootConnectPolicy]).
 * - `LOCKED_BOOT_COMPLETED` (direct-boot) is accepted but our stores live in credential-encrypted
 *   storage, so on a locked device the inputs read as "off / no profile" and the policy skips;
 *   the regular BOOT_COMPLETED after unlock does the work.
 */
public class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BootConnectPolicy.ACCEPTED_ACTIONS) return
        val inputs = VpnServiceRegistry.bootInputs ?: run { Log.i(TAG, "no boot inputs wired; skip"); return }
        val manager = VpnServiceRegistry.connectionManager ?: run { Log.i(TAG, "no connection manager; skip"); return }
        val decision = BootConnectPolicy.decide(
            enabled = inputs.autoConnectEnabled(),
            vpnConsentGranted = VpnService.prepare(context.applicationContext) == null,
            selectedProfileId = inputs.selectedProfileId(),
            lastActiveProfileId = VpnServiceRegistry.lastProfileStore?.read(),
            alreadyActive = manager.state.value.isActive,
        )
        when (decision) {
            is BootConnectPolicy.Decision.Skip -> Log.i(TAG, "auto-connect on boot skipped: ${decision.reason}")
            is BootConnectPolicy.Decision.Connect -> {
                Log.i(TAG, "auto-connect on boot ($action)")
                manager.connect(decision.profileId)
            }
        }
    }

    private companion object { const val TAG = "BootReceiver" }
}

/** What the app wires for the boot receiver; all reads are synchronous SharedPreferences lookups. */
public interface BootInputs {
    public fun autoConnectEnabled(): Boolean
    public fun selectedProfileId(): String?
}
