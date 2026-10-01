package io.github.kickoman.qiyaa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `Belarusian unless another language was stored`() {
        assertEquals(AppLanguage.BELARUSIAN, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.BELARUSIAN, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(listOf("be", "ru", "en"), AppLanguage.entries.map { it.tag })
    }
}
