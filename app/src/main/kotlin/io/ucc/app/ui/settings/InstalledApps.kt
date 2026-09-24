package io.ucc.app.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists launchable/user apps for the per-app routing picker. Needs QUERY_ALL_PACKAGES (declared in :core:vpn). */
object InstalledApps {
    suspend fun load(context: Context, includeSystem: Boolean): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val self = context.packageName
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .filter { pm.checkPermission(android.Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
            .filter { includeSystem || !it.isSystem }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
