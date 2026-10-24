package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.support.localRow
import io.github.kickoman.qiyaa.track.TrackSource
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTracksTest {
    @Test
    fun `LOCAL-04 LOCAL-05 an untagged row is titled by its file name and has the library's cover`() {
        val untagged = localRow(42, null, "A", 1, 0, "Music", file = "Song name.mp3", durationMs = MINUTE)
        val track = LocalTracks.toTrack(untagged.copy(sizeBytes = 2_400_000))
        assertEquals("Song name", track.title)
        assertEquals(emptyList<String>(), track.artists)
        assertEquals(TrackSource.LOCAL, track.source)
        assertEquals("content://media/external/audio/media/42/albumart", track.coverUrl)
        assertEquals(320, track.bitrateKbps)
    }

    @Test
    fun `LOCAL-05 the cover's address has ASCII digits whatever the locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))
            val track = LocalTracks.toTrack(localRow(42, "T", "A", 1, 0, "Music", file = "t.mp3"))
            assertEquals("content://media/external/audio/media/42/albumart", track.coverUrl)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `LOCAL-13 the formats taken, and FLAC only with a decoder`() {
        assertTrue(LocalTracks.isTaken("audio/mpeg", flacDecodes = false))
        assertTrue(LocalTracks.isTaken("application/ogg", flacDecodes = false))
        assertTrue(LocalTracks.isTaken("audio/flac", flacDecodes = true))
        assertFalse(LocalTracks.isTaken("audio/flac", flacDecodes = false))
        assertFalse(LocalTracks.isTaken("audio/aac-adts", flacDecodes = true))
        assertFalse(LocalTracks.isTaken(null, flacDecodes = true))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
