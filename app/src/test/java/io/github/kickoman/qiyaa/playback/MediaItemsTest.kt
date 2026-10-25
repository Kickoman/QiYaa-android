package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackKey
import io.github.kickoman.qiyaa.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaItemsTest {
    @Test
    fun `QUAL-05 a revised track URI is the plain one with the revision as a query`() {
        assertEquals("qiyaa://track/42", MediaItems.uriText("42"))
        assertEquals("qiyaa://track/42", MediaItems.uriText("42", revision = 0))
        assertEquals("qiyaa://track/42?r=3", MediaItems.uriText("42", revision = 3))
    }

    @Test
    fun `a negative revision is refused with the track and the value`() {
        val failed = assertThrows(IllegalArgumentException::class.java) { MediaItems.uriText("42", -1) }
        assertEquals("The revision of track 42 is -1", failed.message)
    }

    @Test
    fun `a Yandex track's media id is its id, a local track's says its source`() {
        val yandex = Track(id = "42", title = "A")
        val local = Track(id = "42", title = "B", source = TrackSource.LOCAL)
        assertEquals("42", MediaItems.mediaIdOf(yandex))
        assertEquals("local:42", MediaItems.mediaIdOf(local))
        for (track in listOf(yandex, local)) {
            val mediaId = MediaItems.mediaIdOf(track)
            assertEquals(track.key, TrackKey(MediaItems.sourceOf(mediaId), MediaItems.idOf(mediaId)))
        }
    }

    @Test
    fun `LOCAL-06 a local track plays from the media library's content URI, a Yandex one through qiyaa`() {
        assertEquals("qiyaa://track/42", MediaItems.uriTextFor(Track(id = "42", title = "A")))
        assertEquals(
            "content://media/external/audio/media/42",
            MediaItems.uriTextFor(Track(id = "42", title = "B", source = TrackSource.LOCAL)),
        )
    }

    @Test
    fun `a Spotify track plays from the URI the spec gives it, spotify track and its id`() {
        val spotify = Track(id = SPOTIFY_ID, title = "S", source = TrackSource.SPOTIFY)
        assertEquals("spotify:track:$SPOTIFY_ID", MediaItems.uriTextFor(spotify))
    }

    @Test
    fun `a Spotify track's media id says its source, and no id of one source reads as another's`() {
        val spotify = Track(id = SPOTIFY_ID, title = "S", source = TrackSource.SPOTIFY)
        assertEquals("spotify:$SPOTIFY_ID", MediaItems.mediaIdOf(spotify))
        val sameId = TrackSource.entries.map { Track(id = "42", title = "A", source = it) }
        for (track in sameId + spotify) {
            val mediaId = MediaItems.mediaIdOf(track)
            assertEquals(track.key, TrackKey(MediaItems.sourceOf(mediaId), MediaItems.idOf(mediaId)))
        }
        assertEquals(sameId.size, sameId.map(MediaItems::mediaIdOf).toSet().size)
    }

    private companion object {
        const val SPOTIFY_ID = "6PCoZdjSPbRMehHviTrpB3"
    }
}
