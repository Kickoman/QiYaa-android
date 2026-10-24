package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.support.Spec
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibSearchTest {
    @Test
    fun `LYR-04 every LRCLIB search fixture gives the expected picks`() {
        val cases = Spec.cases("search", Spec.LRCLIB).filter { it.first().isLetter() }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val records = LrclibSearch.parse(Spec.fixture("search", case, Spec.LRCLIB).body)
            val picks = Spec.expected("search", case, Spec.LRCLIB).getValue("picks").jsonArray
            for (pick in picks.map { it.jsonObject }) {
                val durationMs = pick.getValue("durationMs").jsonPrimitive.long
                val choice = LrclibSearch.pick(records, durationMs)
                val where = "$case at $durationMs ms"
                assertEquals(where, pick.getValue("kind").jsonPrimitive.content, kindOf(choice.answer))
                assertEquals(where, pick.getValue("id").jsonPrimitive.longOrNull, choice.recordId)
            }
        }
    }

    @Test
    fun `LYR-04 a synced text without a single time tag counts as plain`() {
        val record = LrclibRecord(7, 100.0, false, "", "just words\nno times")
        val choice = LrclibSearch.pick(listOf(record), 0)
        assertEquals("plain", kindOf(choice.answer))
        assertEquals(7L, choice.recordId)
    }

    @Test
    fun `LYR-04 the version in round or square brackets at the end of a title is cut`() {
        assertEquals("Song", LrclibSearch.titleWithoutVersion("Song (Live)"))
        assertEquals("Song", LrclibSearch.titleWithoutVersion("Song [Remastered 2011]  "))
        assertEquals("Song (Live)", LrclibSearch.titleWithoutVersion("Song (Live) (Radio Edit)"))
        assertNull(LrclibSearch.titleWithoutVersion("Song"))
        assertNull(LrclibSearch.titleWithoutVersion("(Intro)"))
        assertNull(LrclibSearch.titleWithoutVersion("Song (Live) tail"))
    }

    @Test
    fun `LYR-04 a broken reply finds nothing`() {
        assertEquals(emptyList<LrclibRecord>(), LrclibSearch.parse("<html>busy</html>"))
        assertEquals(emptyList<LrclibRecord>(), LrclibSearch.parse("{\"records\": []}"))
    }

    private fun kindOf(answer: LyricsAnswer): String = when (answer) {
        is LyricsAnswer.Text -> if (answer.lyrics.kind == LyricsKind.SYNCED) "synced" else "plain"
        LyricsAnswer.Instrumental -> "instrumental"
        else -> "missing"
    }
}
