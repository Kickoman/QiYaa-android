package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsSource
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceLyricsTest {
    @Test
    fun `LYR-02 LYR-10 a track's text is asked of its own source`() {
        val asked = ArrayList<String>()
        val yandex = LyricsSource {
            asked += "yandex ${it.id}"
            LyricsAnswer.Missing()
        }
        val local = LyricsSource {
            asked += "local ${it.id}"
            LyricsAnswer.Instrumental
        }
        val lyrics = SourceLyrics(yandex, local)
        runBlocking {
            assertEquals(LyricsAnswer.Missing(), lyrics.lyrics(Track("42", "Y")))
            assertEquals(
                LyricsAnswer.Instrumental,
                lyrics.lyrics(Track("42", "L", source = TrackSource.LOCAL)),
            )
        }
        assertEquals(listOf("yandex 42", "local 42"), asked)
    }
}
