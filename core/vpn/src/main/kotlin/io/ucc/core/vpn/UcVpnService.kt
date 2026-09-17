package io.ucc.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ucc.core.engine.ConnectionState
import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.TunRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Foreground VpnService that owns the TUN descriptor.
 *
 * It deliberately contains no networking logic: the ConnectionManager decides
 * *when* to connect, the core decides *what* the TUN looks like (via
 * [TunRequest]); this class only translates that into `VpnService.Builder`
 * calls, keeps the process alive with a foreground notification, and reports
 * revocation.
 *
 * Restart semantics: the service is `START_STICKY` only while a profile is
 * active. If Android recreates it after process death, [onStartCommand] sees
 * a null intent and asks the ConnectionManager to reconnect to the last
 * profile (persisted via [LastProfileStore]) — nothing is faked; a real
 * connect runs again.
 */
public class UcVpnService : VpnService(), TunProvider {

    public companion object {
        private const val TAG = "UcVpnService"
        public const val ACTION_START: String = "io.ucc.vpn.START"
        public const val ACTION_STOP: String = "io.ucc.vpn.STOP"
        public const val EXTRA_PROFILE_ID: String = "profile_id"
        private const val CHANNEL_ID = "ucc.connection"
        private const val NOTIFICATION_ID = 1001

        public fun startIntent(context: Context, profileId: String): Intent =
            Intent(context, UcVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_PROFILE_ID, profileId)

        public fun stopIntent(context: Context): Intent =
            Intent(context, UcVpnService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var activeProfileId: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        VpnServiceRegistry.onServiceCreated(this)
        publishLockdownStatus()
    }

    /** Reads the real always-on/lockdown flags; re-read on every start so a settings change is picked up. */
    private fun publishLockdownStatus() {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            LockdownStatus(supported = true, alwaysOn = isAlwaysOn, lockdown = isLockdownEnabled, observedAtEpochMs = System.currentTimeMillis())
        } else {
            LockdownStatus(supported = false, alwaysOn = false, lockdown = false, observedAtEpochMs = System.currentTimeMillis())
        }
        VpnServiceRegistry.lockdownStatus.value = status
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        publishLockdownStatus()
        when (intent?.action) {
            ACTION_STOP -> {
                VpnServiceRegistry.connectionManager?.disconnect()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                activeProfileId = profileId
                VpnServiceRegistry.lastProfileStore?.write(profileId)
                goForeground(getString(R.string.vpn_notification_title_starting), null)
                observeStateForNotification()
                return START_STICKY
            }
            null -> {
                // System restarted us after process death. Resume the last profile if any.
                val last = VpnServiceRegistry.lastProfileStore?.read()
                val manager = VpnServiceRegistry.connectionManager
                if (last != null && manager != null && !manager.state.value.isActive) {
                    Log.i(TAG, "restarted by system; reconnecting last profile")
                    goForeground(getString(R.string.vpn_notification_title_starting), null)
                    observeStateForNotification()
                    manager.connect(last)
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        VpnServiceRegistry.revoked.tryEmit(Unit)
        closeTun()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        closeTun()
        VpnServiceRegistry.onServiceDestroyed(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------- TunProvider

    override fun openTun(request: TunRequest): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        closeTun()

        val builder = Builder()
            .setSession(getString(applicationInfo.labelRes.takeIf { it != 0 } ?: R.string.vpn_notification_channel))
            .setMtu(request.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        request.inet4Addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        request.inet6Addresses.forEach { builder.addAddress(it.address, it.prefixLength) }

        if (request.autoRoute) {
            request.dnsServers.forEach { builder.addDnsServer(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (request.inet4Routes.isNotEmpty()) {
                    request.inet4Routes.forEach { builder.addRoute(it.toIpPrefix()) }
                } else if (request.inet4Addresses.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                if (request.inet6Routes.isNotEmpty()) {
                    request.inet6Routes.forEach { builder.addRoute(it.toIpPrefix()) }
                } else if (request.inet6Addresses.isNotEmpty()) {
                    builder.addRoute("::", 0)
                }
                request.inet4ExcludedRoutes.forEach { builder.excludeRoute(it.toIpPrefix()) }
                request.inet6ExcludedRoutes.forEach { builder.excludeRoute(it.toIpPrefix()) }
            } else {
                // Pre-33: the core hands us pre-computed route ranges that already exclude what must be excluded.
                request.inet4Routes.forEach { builder.addRoute(it.address, it.prefixLength) }
                request.inet6Routes.forEach { builder.addRoute(it.address, it.prefixLength) }
            }

            request.includePackages.forEach { pkg ->
                try { builder.addAllowedApplication(pkg) } catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "allowed app missing: $pkg") }
            }
            request.excludePackages.forEach { pkg ->
                try { builder.addDisallowedApplication(pkg) } catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "disallowed app missing: $pkg") }
            }
        }

        val proxy = request.httpProxy
        if (proxy != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(proxy.host, proxy.port, proxy.bypassDomains))
        }

        val pfd = builder.establish() ?: error("android: establish() returned null (permission revoked or another VPN active)")
        tunFd = pfd
        VpnServiceRegistry.tunState.value = TunState(
            fd = pfd.fd, mtu = request.mtu,
            addresses = (request.inet4Addresses + request.inet6Addresses).map { "${it.address}/${it.prefixLength}" },
            routes = (request.inet4Routes + request.inet6Routes).map { "${it.address}/${it.prefixLength}" }.ifEmpty { if (request.autoRoute) listOf("0.0.0.0/0", "::/0 (if v6)") else emptyList() },
            excludedRoutes = (request.inet4ExcludedRoutes + request.inet6ExcludedRoutes).map { "${it.address}/${it.prefixLength}" },
            dnsServers = request.dnsServers,
            includedPackages = request.includePackages.size, excludedPackages = request.excludePackages.size,
            establishedAtEpochMs = System.currentTimeMillis(),
        )
        return pfd.fd
    }

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    internal fun closeTun() {
        tunFd?.let { runCatching { it.close() } }
        tunFd = null
        VpnServiceRegistry.tunState.value = null
    }

    /** Called by [AndroidTunnelHost.release]. */
    internal fun shutdown() {
        activeProfileId = null
        VpnServiceRegistry.lastProfileStore?.write(null)
        notificationJob?.cancel()
        closeTun()
        stopForegroundCompat()
        stopSelf()
    }

    // ------------------------------------------------------------ notification

    private fun observeStateForNotification() {
        val flow = VpnServiceRegistry.stateForNotification ?: return
        notificationJob?.cancel()
        notificationJob = scope.launch {
            flow.collectLatest { state ->
                val name = state.profileIdOrNull?.let { id -> VpnServiceRegistry.profileNameLookup?.invoke(id) }
                val title = when (state) {
                    is ConnectionState.Starting, is ConnectionState.Connecting -> getString(R.string.vpn_notification_title_starting)
                    is ConnectionState.Connected -> getString(R.string.vpn_notification_title_connected)
                    is ConnectionState.Reconnecting -> getString(R.string.vpn_notification_title_reconnecting)
                    is ConnectionState.Stopping -> getString(R.string.vpn_notification_title_stopping)
                    else -> return@collectLatest
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(title, name))
            }
        }
    }

    private fun goForeground(title: String, text: String?) {
        val n = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = VpnServiceRegistry.launchIntentFactory?.invoke(this)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val stopIntent = PendingIntent.getService(this, 1, stopIntent(this), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .addAction(0, getString(R.string.vpn_notification_action_disconnect), stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.vpn_notification_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun io.ucc.core.engine.IpPrefix.toIpPrefix(): IpPrefix = IpPrefix(InetAddress.getByName(address), prefixLength)
}
