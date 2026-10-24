package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.lyrics.Lyrics
import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsFrom
import io.github.kickoman.qiyaa.lyrics.LyricsKind
import io.github.kickoman.qiyaa.lyrics.LyricsLine
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.MalformedResponseException
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class YandexLyricsTest {
    private val track = Track("42", "Song", listOf("Band"))

    @Test
    fun `LYR-02 the catalog's text is shown as plain, even with what looks like time tags`() {
        val answer = ask { "[00:01]first\nsecond" }
        val lines = listOf(LyricsLine(null, "[00:01]first"), LyricsLine(null, "second"))
        assertEquals(LyricsAnswer.Text(Lyrics(LyricsKind.PLAIN, lines), LyricsFrom.CATALOG), answer)
    }

    @Test
    fun `LYR-02 without a text, or with a text that leaves no line, the source has none`() {
        assertEquals(LyricsAnswer.Missing(), ask { null })
        assertEquals(LyricsAnswer.Missing(), ask { "\n  \n" })
    }

    @Test
    fun `LYR-02 an HTTP error below 500 or a malformed reply is none, a 5xx or the network a failure`() {
        assertEquals(
            LyricsAnswer.Missing(),
            ask {
                throw HttpException(400, "GET", "/tracks/42/supplement", "x")
            },
        )
        assertEquals(
            LyricsAnswer.Missing(),
            ask {
                throw MalformedResponseException("GET", "/p", "no result")
            },
        )
        assertEquals(
            LyricsAnswer.Failed,
            ask {
                throw HttpException(503, "GET", "/tracks/42/supplement", "x")
            },
        )
        assertEquals(LyricsAnswer.Failed, ask { throw NetworkException("GET", "/p", IOException("reset")) })
    }

    @Test
    fun `LYR-16 without a login nothing is asked, and the answer says a login is needed`() {
        var asked = false
        val lyrics = YandexLyrics({
            asked = true
            null
        }, signedIn = { false })
        assertEquals(LyricsAnswer.NoAccount, runBlocking { lyrics.lyrics(track) })
        assertEquals(false, asked)
    }

    @Test
    fun `LYR-02 the track's id is what is asked`() {
        var asked = ""
        ask {
            asked = it
            null
        }
        assertEquals("42", asked)
    }

    private fun ask(fetch: suspend (String) -> String?): LyricsAnswer =
        runBlocking { YandexLyrics(fetch, signedIn = { true }).lyrics(track) }
}
