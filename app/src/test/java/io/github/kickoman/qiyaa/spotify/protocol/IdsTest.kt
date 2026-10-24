package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import org.junit.Assert.assertEquals
import org.junit.Test

class IdsTest {
    @Test
    fun `every case of spec spotify ids json converts both ways or is refused`() {
        SpotifyVectors.forEachCase("ids") { case ->
            val base62 = case.text("base62")
            val gid = case.hexOrNull("gid")
            assertEquals(gid?.toHex(), Ids.gidFromBase62(base62)?.hex())
            if (gid != null) assertEquals(base62, Ids.base62FromGid(Bytes.of(gid)))
            assertEquals(case.textOrNull("uri"), Ids.trackUri(base62))
        }
    }
}
