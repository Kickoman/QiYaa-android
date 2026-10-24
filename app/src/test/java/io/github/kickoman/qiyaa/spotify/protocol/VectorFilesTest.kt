package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.Spec
import io.github.kickoman.qiyaa.support.SpotifyVectors
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorFilesTest {
    @Test
    fun `every file of spec spotify has a test that reads all of it`() {
        assertEquals(SpotifyVectors.FILES, Spec.spotifyFiles())
    }
}
