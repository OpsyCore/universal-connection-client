package io.ucc.app.data

import io.ucc.applogic.LanguageStore
import io.ucc.applogic.AppLanguage
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Production [LanguageStore] on top of AndroidX per-app locales.
 *
 * - API 33+: the OS stores the choice (also visible in system Settings → App languages).
 * - API < 33: AppCompat stores it (`autoStoreLocales` meta-data in the manifest) and re-applies
 *   it on process start, before the first Activity is created.
 * Setting a value recreates the running activities so the new locale/direction applies immediately.
 */
class AppCompatLanguageStore : LanguageStore {
    private val flow = MutableStateFlow(AppLanguage.fromTag(AppCompatDelegate.getApplicationLocales().toLanguageTags()))
    override val languageFlow: StateFlow<AppLanguage> = flow

    override var language: AppLanguage
        get() = flow.value
        set(value) {
            flow.value = value
            val locales = value.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(locales)
        }
}
