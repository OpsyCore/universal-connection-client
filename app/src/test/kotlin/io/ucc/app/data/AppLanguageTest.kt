package io.ucc.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test fun `empty or null tag means system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("   "))
    }

    @Test fun `region and script subtags are ignored`() {
        assertEquals(AppLanguage.PERSIAN, AppLanguage.fromTag("fa-IR"))
        assertEquals(AppLanguage.PERSIAN, AppLanguage.fromTag("fa_AF"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("EN"))
    }

    @Test fun `first locale of a list wins and unknown falls back to system`() {
        assertEquals(AppLanguage.PERSIAN, AppLanguage.fromTag("fa-IR,en-US"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("de-DE"))
    }

    @Test fun `every shipped language has a resource tag`() {
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { check(!it.tag.isNullOrBlank()) { it.name } }
    }

    @Test fun `in-memory store round trips`() {
        val store = LanguageStore.InMemory()
        store.language = AppLanguage.PERSIAN
        assertEquals(AppLanguage.PERSIAN, store.languageFlow.value)
    }
}
