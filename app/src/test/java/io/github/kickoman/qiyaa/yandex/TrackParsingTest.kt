package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParsingTest {
    @Test
    fun `parseTrack reads ids, version, artists, album cover and web URL`() {
        val track =
            TrackParsing.parseTrack(
                Json.parseToJsonElement(
                    """{"id":"12345","title":"Song","version":"Live","artists":[{"name":"A"},{"name":"B"}],
                    "albums":[{"id":777,"coverUri":"avatars.yandex.net/get-music-content/1/abc/%%"}],
                    "durationMs":201000,"available":true}""",
                ),
            )
        assertEquals("12345", track.id)
        assertEquals("777", track.albumId)
        assertEquals("A, B - Song (Live)", track.displayTitle)
        assertEquals(201000L, track.durationMs)
        assertTrue(track.available)
        assertEquals("https://avatars.yandex.net/get-music-content/1/abc/400x400", track.coverUrl)
        assertEquals("https://music.yandex.ru/album/777/track/12345", track.webUrl)
    }

    @Test
    fun `parseTrackArray unwraps track wrappers and applies defaults`() {
        val list =
            TrackParsing.parseTrackArray(
                Json.parseToJsonElement(
                    """[{"id":5,"track":{"id":5,"title":"Five"}},{"id":6,"title":"Six","available":false}]""",
                ),
            )
        assertEquals(listOf("Five", "Six"), list.map { it.title })
        assertEquals("5", list[0].id)
        assertNull(list[0].coverUrl)
        assertTrue(list[0].available)
        assertFalse(list[1].available)
        assertEquals("https://music.yandex.ru/track/6", list[1].webUrl)
    }

    @Test
    fun `formatSeconds renders integral seconds without a fraction like QString number`() {
        assertEquals("180", YandexApi.formatSeconds(180000))
        assertEquals("201.5", YandexApi.formatSeconds(201500))
    }
}
