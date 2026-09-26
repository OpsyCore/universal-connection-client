package io.ucc.core.vpn

import android.content.Context
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.manager.TunnelHost
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [TunnelHost] that starts [UcVpnService] as a foreground service and waits
 * for the instance to appear. VPN *permission* must already be granted by the
 * UI (via `VpnService.prepare` + activity result); this class only verifies
 * it and never shows UI.
 */
public class AndroidTunnelHost(context: Context) : TunnelHost {
    private val app = context.applicationContext

    override val revoked: Flow<Unit> = VpnServiceRegistry.revoked

    override suspend fun acquire(profile: ConnectionProfile): TunProvider {
        if (VpnService.prepare(app) != null) {
            throw CoreException(ConnectionError.VpnPermissionDenied())
        }
        VpnServiceRegistry.instance?.let { existing ->
            // Service already running (profile switch); reuse it.
            ContextCompat.startForegroundService(app, UcVpnService.startIntent(app, profile.id))
            return existing
        }
        val waiter = CompletableDeferred<UcVpnService>()
        VpnServiceRegistry.waiter = waiter
        ContextCompat.startForegroundService(app, UcVpnService.startIntent(app, profile.id))
        val service = withTimeoutOrNull(SERVICE_START_TIMEOUT_MS) { waiter.await() }
        if (service == null) {
            VpnServiceRegistry.waiter = null
            throw CoreException(
                ConnectionError.CoreFailure(
                    "VpnService did not start within ${SERVICE_START_TIMEOUT_MS}ms" +
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " (background start restriction?)" else "",
                ),
            )
        }
        return service
    }

    override suspend fun release() {
        VpnServiceRegistry.instance?.shutdown()
    }

    private companion object {
        const val SERVICE_START_TIMEOUT_MS = 10_000L
    }
}
