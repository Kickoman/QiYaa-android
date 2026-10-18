package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.Quality
import io.github.kickoman.qiyaa.yandex.ResolvedUrl
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayLinkTest {
    private var now = 0L
    private var quality = Quality.STANDARD
    private var offline = false
    private val signed = ArrayList<String>()
    private val cache =
        TrackUrlCache(clock = { now }, quality = { quality }, ttlMs = 1_000) { id, asked ->
            if (offline) throw IOException("offline")
            signed += if (asked == Quality.STANDARD) id else "$id:$asked"
            ResolvedUrl("https://storage/$id/${signed.size}", if (asked == Quality.STANDARD) 320 else 64)
        }
    private val shown = ArrayList<Int>()
    private val bitrate = CurrentBitrate { shown += it }

    @Test
    fun `QUAL-08 a play reads on in the quality of its own first signing`() = runBlocking {
        val play = PlayLink(cache, bitrate)
        assertEquals("https://storage/1/1", play.url("1"))
        quality = Quality.ECONOMY
        assertEquals("https://storage/1/1", play.url("1"))
        now = 1_000
        assertEquals("https://storage/1/2", play.url("1"))
        assertEquals(listOf("1", "1"), signed)
    }

    @Test
    fun `QUAL-08 a play whose first open hits the cache keeps that quality for its reads on`() = runBlocking {
        PlayLink(cache, bitrate).url("1")
        val replay = PlayLink(cache, bitrate)
        assertEquals("https://storage/1/1", replay.url("1"))
        quality = Quality.ECONOMY
        assertEquals("https://storage/1/1", replay.url("1"))
        assertEquals(listOf("1"), signed)
    }

    @Test
    fun `QUAL-08 QUAL-05 a new start takes the quality in effect although the old quality's link is fresh`() =
        runBlocking {
            PlayLink(cache, bitrate).url("1")
            quality = Quality.ECONOMY
            val again = PlayLink(cache, bitrate)
            assertEquals("https://storage/1/2", again.url("1"))
            quality = Quality.STANDARD
            assertEquals("https://storage/1/2", again.url("1"))
            assertEquals(listOf("1", "1:ECONOMY"), signed)
        }

    @Test
    fun `QUAL-08 in a one-track repeat a quality change between laps leaves the playing lap on its file`() =
        runBlocking {
            val lap = PlayLink(cache, bitrate)
            lap.url("1")
            quality = Quality.ECONOMY
            val nextLap = PlayLink(cache, bitrate)
            assertEquals("https://storage/1/2", nextLap.url("1"))
            assertEquals("https://storage/1/1", lap.url("1"))
            assertEquals("https://storage/1/2", nextLap.url("1"))
            assertEquals(listOf("1", "1:ECONOMY"), signed)
        }

    @Test
    fun `QUAL-08 a failed first signing leaves the play's quality to its next open`() = runBlocking {
        val play = PlayLink(cache, bitrate)
        quality = Quality.ECONOMY
        offline = true
        assertTrue(runCatching { play.url("1") }.isFailure)
        offline = false
        quality = Quality.STANDARD
        assertEquals("https://storage/1/1", play.url("1"))
        quality = Quality.ECONOMY
        assertEquals("https://storage/1/1", play.url("1"))
        assertEquals(listOf("1"), signed)
    }

    @Test
    fun `the bitrate is reported by a play's first open only`() = runBlocking {
        bitrate.onCurrentChanged("1")
        val play = PlayLink(cache, bitrate)
        play.url("1")
        quality = Quality.ECONOMY
        play.url("1")
        now = 1_000
        play.url("1")
        assertEquals(listOf(0, 320), shown)
    }

    @Test
    fun `a next lap of the playing track in another quality shows its bitrate before the transition`() =
        runBlocking {
            bitrate.onCurrentChanged("1")
            PlayLink(cache, bitrate).url("1")
            quality = Quality.ECONOMY
            PlayLink(cache, bitrate).url("1")
            assertEquals(listOf(0, 320, 64), shown)
        }
}
