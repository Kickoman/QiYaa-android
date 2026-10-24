package io.github.kickoman.qiyaa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAccessTest {
    @Test
    fun `LOCAL-10 before the first answer the library offers to ask`() {
        assertEquals(LocalAccess.ASK, LocalAccessRule.before(granted = false, askedBefore = false))
    }

    @Test
    fun `LOCAL-10 a granted permission shows the picks, asked or not`() {
        assertEquals(LocalAccess.GRANTED, LocalAccessRule.before(granted = true, askedBefore = false))
        assertEquals(LocalAccess.GRANTED, LocalAccessRule.before(granted = true, askedBefore = true))
        assertEquals(
            LocalAccess.GRANTED,
            LocalAccessRule.after(granted = true, rationale = false, askedBefore = true),
        )
    }

    @Test
    fun `LOCAL-10 a refusal offers to ask again`() {
        assertEquals(
            LocalAccess.ASK_AGAIN,
            LocalAccessRule.after(granted = false, rationale = true, askedBefore = false),
        )
        assertEquals(
            LocalAccess.ASK_AGAIN,
            LocalAccessRule.after(granted = false, rationale = true, askedBefore = true),
        )
    }

    @Test
    fun `LOCAL-10 a first refusal without a rationale still offers to ask again`() {
        assertEquals(
            LocalAccess.ASK_AGAIN,
            LocalAccessRule.after(granted = false, rationale = false, askedBefore = false),
        )
    }

    @Test
    fun `LOCAL-10 a refusal for good offers the app's settings`() {
        assertEquals(
            LocalAccess.SETTINGS,
            LocalAccessRule.after(granted = false, rationale = false, askedBefore = true),
        )
    }

    @Test
    fun `LOCAL-10 after a restart a refused permission offers to ask again`() {
        assertEquals(LocalAccess.ASK_AGAIN, LocalAccessRule.before(granted = false, askedBefore = true))
    }
}
