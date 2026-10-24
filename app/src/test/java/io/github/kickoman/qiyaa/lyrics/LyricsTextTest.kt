package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.support.Spec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTextTest {
    private val vectors = Spec.lyrics()

    @Test
    fun `LYR-11 the limits are the spec's`() {
        val limits = vectors.getValue("limits").jsonObject
        assertEquals(limits.getValue("maxBytes").jsonPrimitive.int, LyricsText.MAX_BYTES)
        assertEquals(limits.getValue("maxLines").jsonPrimitive.int, LyricsText.MAX_LINES)
        assertEquals(limits.getValue("maxLineCharacters").jsonPrimitive.int, LyricsText.MAX_LINE_CHARACTERS)
    }

    @Test
    fun `LYR-12 every case of spec lyrics lrc json gives its lines`() {
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val text = case.getValue("text").jsonPrimitive.content
            assertEquals(name, expected(case), LyricsText.parse(text))
        }
    }

    @Test
    fun `LYR-11 a text over 256 KiB in UTF-8 is not taken`() {
        val line = "ж".repeat(1_000)
        val text = List(LyricsText.MAX_BYTES / 2_000 + 1) { line }.joinToString("\n")
        assertNull(LyricsText.parse(text))
        assertNull(LyricsText.plain(text))
    }

    @Test
    fun `LYR-11 only the first 2000 lines are kept, synced or plain`() {
        val synced = (0 until 2_100).joinToString("\n") { "[00:00.${it % 100}]line $it" }
        assertEquals(LyricsText.MAX_LINES, LyricsText.parse(synced)?.lines?.size)
        val plain = (0 until 2_100).joinToString("\n") { "line $it" }
        assertEquals(LyricsText.MAX_LINES, LyricsText.parse(plain)?.lines?.size)
    }

    @Test
    fun `LYR-11 a cut that would split a surrogate pair leaves the pair out`() {
        val text = "a".repeat(LyricsText.MAX_LINE_CHARACTERS - 1) + "🎵" + "tail"
        assertEquals(
            "a".repeat(LyricsText.MAX_LINE_CHARACTERS - 1),
            LyricsText.plain(text)?.lines?.single()?.text,
        )
    }

    @Test
    fun `LYR-02 a source's plain text stays plain even when a line looks like a time tag`() {
        val lyrics = LyricsText.plain("[00:01]first\n\n\nsecond")
        assertEquals(
            Lyrics(
                LyricsKind.PLAIN,
                listOf(LyricsLine(null, "[00:01]first"), LyricsLine(null, ""), LyricsLine(null, "second")),
            ),
            lyrics,
        )
    }

    private fun expected(case: JsonObject): Lyrics? {
        val kind = case.getValue("kind")
        if (kind is JsonNull) return null
        val lines =
            case.getValue("lines").jsonArray.map {
                val line = it.jsonObject
                LyricsLine(
                    line.getValue("ms").jsonPrimitive.longOrNull,
                    line.getValue("text").jsonPrimitive.content,
                )
            }
        val parsedKind = if (kind.jsonPrimitive.content == "synced") LyricsKind.SYNCED else LyricsKind.PLAIN
        return Lyrics(parsedKind, lines)
    }
}
