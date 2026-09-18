package io.ucc.iosinfra

import io.ucc.applogic.AppLanguage
import io.ucc.applogic.ConnectionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreferencesTest {
    @Test fun keys_match_android_preferences() {
        assertEquals("selected_profile_id", KeyValuePreferences.KEY_SELECTED)
        assertEquals("last_active_profile_id", KeyValuePreferences.KEY_LAST_ACTIVE)
        assertEquals("smart_selection", KeyValuePreferences.KEY_SMART)
        assertEquals("connection_settings_v1", KeyValueSettingsStore.KEY)
    }

    @Test fun selection_and_last_profile_persist_and_reload() {
        val kv = InMemoryKeyValueStore()
        val p = KeyValuePreferences(kv)
        assertNull(p.selectedProfileId); assertFalse(p.smartMode); assertNull(p.read())
        p.selectedProfileId = "p1"; p.smartMode = true; p.write("p2")
        assertEquals("p1", kv.values["selected_profile_id"]); assertEquals("p2", kv.values["last_active_profile_id"]); assertEquals(true, kv.values["smart_selection"])
        val again = KeyValuePreferences(kv)
        assertEquals("p1", again.selectedProfileId); assertEquals("p1", again.selectedProfileIdFlow.value)
        assertTrue(again.smartMode); assertEquals("p2", again.read())
        p.selectedProfileId = null; p.write(null)
        assertFalse(kv.values.containsKey("selected_profile_id")); assertNull(KeyValuePreferences(kv).read())
    }

    @Test fun language_round_trips_by_tag() {
        val kv = InMemoryKeyValueStore(); val p = KeyValuePreferences(kv)
        assertEquals(AppLanguage.SYSTEM, p.language)
        val nonSystem = AppLanguage.entries.first { it.tag != null }
        p.language = nonSystem
        assertEquals(nonSystem.tag, kv.values["app_language"])
        assertEquals(nonSystem, KeyValuePreferences(kv).language)
        kv.values["app_language"] = "zz-unknown"
        assertEquals(AppLanguage.SYSTEM, KeyValuePreferences(kv).language)
    }

    @Test fun settings_json_round_trip_and_tolerance() {
        val kv = InMemoryKeyValueStore()
        val s = KeyValueSettingsStore(kv)
        assertEquals(ConnectionSettings(), s.settings.value)
        val before = kv.values.size
        s.update { it }; assertEquals(before, kv.values.size) // unchanged → no write
        s.update { it.copy(mtu = 1500, bypassPrivate = false) }
        val stored = kv.values[KeyValueSettingsStore.KEY] as String
        assertTrue(stored.contains("\"mtu\":1500"), stored)
        assertEquals(1500, KeyValueSettingsStore(kv).settings.value.mtu)
        assertFalse(KeyValueSettingsStore(kv).settings.value.bypassPrivate)
        kv.values[KeyValueSettingsStore.KEY] = "{not json"
        assertEquals(ConnectionSettings(), KeyValueSettingsStore(kv).settings.value)
        kv.values[KeyValueSettingsStore.KEY] = "{\"unknownFutureField\":1}"
        assertEquals(ConnectionSettings(), KeyValueSettingsStore(kv).settings.value)
    }
}
