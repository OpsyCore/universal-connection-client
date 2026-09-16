package io.ucc.app.data

import android.content.Context
import android.content.SharedPreferences
import io.ucc.core.vpn.LastProfileStore

/**
 * Tiny synchronous preference holder for values the VpnService must read on
 * its main thread during `onStartCommand` (DataStore would require blocking).
 * Only non-secret identifiers are stored here.
 */
class Preferences(context: Context) : LastProfileStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("ucc_prefs", Context.MODE_PRIVATE)

    var selectedProfileId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    override fun write(profileId: String?) {
        prefs.edit().putString(KEY_LAST_ACTIVE, profileId).commit()
    }

    override fun read(): String? = prefs.getString(KEY_LAST_ACTIVE, null)

    private companion object {
        const val KEY_SELECTED = "selected_profile_id"
        const val KEY_LAST_ACTIVE = "last_active_profile_id"
    }
}
