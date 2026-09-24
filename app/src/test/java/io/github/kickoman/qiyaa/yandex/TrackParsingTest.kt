package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParsingTest {
    @Test
    fun parsesTrack() {
        val t = YandexApi.parseTrack(
            Json.parseToJsonElement(
                """{"id":"12345","title":"Song","version":"Live","artists":[{"name":"A"},{"name":"B"}],
                    "albums":[{"id":777,"coverUri":"avatars.yandex.net/get-music-content/1/abc/%%"}],"durationMs":201000,"available":true}""",
            ),
        )
        assertEquals("12345", t.id)
        assertEquals("777", t.albumId)
        assertEquals("A, B - Song (Live)", t.displayTitle)
        assertEquals(201000L, t.durationMs)
        assertTrue(t.available)
        assertEquals("https://avatars.yandex.net/get-music-content/1/abc/400x400", t.coverUrl)
        assertEquals("https://music.yandex.ru/album/777/track/12345", t.webUrl)
    }

    @Test
    fun parsesWrappedTracksAndDefaults() {
        val list = YandexApi.parseTrackArray(Json.parseToJsonElement("""[{"id":5,"track":{"id":5,"title":"Five"}},{"id":6,"title":"Six","available":false}]"""))
        assertEquals(listOf("Five", "Six"), list.map { it.title })
        assertEquals("5", list[0].id)
        assertNull(list[0].coverUrl)
        assertTrue(list[0].available)
        assertTrue(!list[1].available)
        assertEquals("https://music.yandex.ru/track/6", list[1].webUrl)
    }

    @Test
    fun formatsTrackLengthLikeQt() {
        assertEquals("180", YandexApi.formatSeconds(180000))
        assertEquals("201.5", YandexApi.formatSeconds(201500))
    }
}
