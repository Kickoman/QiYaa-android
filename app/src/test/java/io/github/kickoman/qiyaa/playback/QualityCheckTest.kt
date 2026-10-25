package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityCheckTest {
    private val yandexAsked = ArrayList<String>()
    private val spotifyAsked = ArrayList<String>()

    @Test
    fun `QUAL-05 a Yandex track is asked of the link cache and a Spotify track of its files, by id`() {
        assertTrue(check(Track(id = "42", title = "Y"), yandex = true))
        assertFalse(check(Track(id = "43", title = "Y"), yandex = false))
        assertTrue(check(Track(id = SPOTIFY_ID, title = "S", source = TrackSource.SPOTIFY), spotify = true))
        assertEquals(listOf("42", "43"), yandexAsked)
        assertEquals(listOf(SPOTIFY_ID), spotifyAsked)
    }

    @Test
    fun `QUAL-05 LOCAL-06 a local file is never in another quality, and nothing is asked about it`() {
        val local = Track(id = "42", title = "L", source = TrackSource.LOCAL)
        assertFalse(check(local, yandex = true, spotify = true))
        assertEquals(emptyList<String>(), yandexAsked + spotifyAsked)
    }

    private fun check(track: Track, yandex: Boolean = false, spotify: Boolean = false): Boolean =
        QualityCheck.askedInOtherQuality(
            track,
            yandex = { id ->
                yandexAsked += id
                yandex
            },
            spotify = { id ->
                spotifyAsked += id
                spotify
            },
        )

    private companion object {
        const val SPOTIFY_ID = "6PCoZdjSPbRMehHviTrpB3"
    }
}
