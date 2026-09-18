package io.ucc.applogic

/**
 * Languages the app ships. `tag` is a BCP‑47 tag matching a `values-<tag>`
 * resource folder and an entry in `res/xml/locales_config.xml`.
 *
 * Adding a language = add `values-xx/strings.xml` (+ core:vpn strings), add
 * `<locale android:name="xx"/>` to locales_config.xml, add one entry here.
 * No UI code changes are required.
 */
enum class AppLanguage(val tag: String?) {
    /** Follow the Android system language (default). */
    SYSTEM(null),
    ENGLISH("en"),
    PERSIAN("fa"),
    ;

    companion object {
        /** Maps a stored/OS locale tag list (e.g. "fa", "fa-IR", "en-US", "") back to an entry. */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val primary = tag.split(',').first().trim().substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.tag == primary } ?: SYSTEM
        }
    }
}

/** Read/write the user's language choice; abstracted so view-models are testable without Android. */
interface LanguageStore {
    val languageFlow: kotlinx.coroutines.flow.StateFlow<AppLanguage>
    var language: AppLanguage

    /** In-memory implementation used by tests and as a default. */
    class InMemory(initial: AppLanguage = AppLanguage.SYSTEM) : LanguageStore {
        private val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override val languageFlow: kotlinx.coroutines.flow.StateFlow<AppLanguage> = flow
        override var language: AppLanguage
            get() = flow.value
            set(value) { flow.value = value }
    }
}
