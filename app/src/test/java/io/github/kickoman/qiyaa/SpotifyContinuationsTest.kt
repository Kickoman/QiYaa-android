package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.queue.ContinuationKey
import io.github.kickoman.qiyaa.spotify.ContextPage
import io.github.kickoman.qiyaa.spotify.SpotifyContentException
import io.github.kickoman.qiyaa.spotify.SpotifyItem
import io.github.kickoman.qiyaa.spotify.SpotifyPick
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyContinuationsTest {
    private val catalog = FakeCatalog()
    private val continuations = SpotifyContinuations(catalog)

    @Test
    fun `SPOT-20 autoplay asks with the context and the started Spotify tracks, oldest first, at most 50`() =
        runTest {
            catalog.pages += ContextPage(listOf(spotify("x1"), spotify("x2")), next = "hm://autoplay/page/2")
            val started =
                (1..55).map { spotify("s$it") } + Track("7", "Яндекс") + spotify("s56") +
                    Track("l1", "Свой", source = TrackSource.LOCAL)
            val tracks = continuations.autoplay("spotify:playlist:p1").more(started)
            assertEquals(listOf("x1", "x2"), tracks.map(Track::id))
            val recent = (7..56).map { "spotify:track:s$it" }
            assertEquals(listOf("autoplay spotify:playlist:p1 $recent"), catalog.calls)
        }

    @Test
    fun `SPOT-20 then the next page, and after a page without a next one autoplay asks anew`() = runTest {
        catalog.pages += ContextPage(listOf(spotify("x1")), next = "hm://autoplay/page/2")
        catalog.pages += ContextPage(listOf(spotify("x2")), next = "")
        catalog.pages += ContextPage(listOf(spotify("x3")), next = "")
        val autoplay = continuations.autoplay("spotify:user:me:collection")
        autoplay.more(listOf(spotify("s1")))
        autoplay.more(listOf(spotify("s1"), spotify("s2")))
        val third = autoplay.more(listOf(spotify("s1"), spotify("s2"), spotify("s3")))
        assertEquals(listOf("x3"), third.map(Track::id))
        assertEquals(
            listOf(
                "autoplay spotify:user:me:collection [spotify:track:s1]",
                "next hm://autoplay/page/2",
                "autoplay spotify:user:me:collection [spotify:track:s1, spotify:track:s2, spotify:track:s3]",
            ),
            catalog.calls,
        )
    }

    @Test
    fun `SPOT-12 the station asks its first page, then the next one, and after the last the station again`() =
        runTest {
            catalog.pages += ContextPage(listOf(spotify("r1")), next = "hm://station/page/2")
            catalog.pages += ContextPage(listOf(spotify("r2")), next = "")
            catalog.pages += ContextPage(listOf(spotify("r3")), next = "")
            val station = continuations.station("spotify:track:r")
            val pages = (1..3).map { station.more(listOf(spotify("s1"))).map(Track::id) }
            assertEquals(listOf(listOf("r1"), listOf("r2"), listOf("r3")), pages)
            assertEquals(
                listOf("station spotify:track:r", "next hm://station/page/2", "station spotify:track:r"),
                catalog.calls,
            )
        }

    @Test
    fun `SPOT-21 a failed page is asked again the next time`() = runTest {
        catalog.pages += ContextPage(listOf(spotify("x1")), next = "hm://autoplay/page/2")
        val autoplay = continuations.autoplay("spotify:album:a")
        autoplay.more(emptyList())
        catalog.failure = SpotifyContentException("a context that cannot be read")
        val failed = runCatching { autoplay.more(emptyList()) }.exceptionOrNull()
        assertEquals("a context that cannot be read", failed?.message)
        catalog.failure = null
        catalog.pages += ContextPage(listOf(spotify("x2")), next = "")
        autoplay.more(emptyList())
        assertEquals(
            listOf("autoplay spotify:album:a []", "next hm://autoplay/page/2", "next hm://autoplay/page/2"),
            catalog.calls,
        )
    }

    @Test
    fun `the key names the kind and the URI, and a key from the file makes the same continuation`() =
        runTest {
            val autoplay = ContinuationKey(SpotifyContinuations.AUTOPLAY, "spotify:artist:a")
            val station = ContinuationKey(SpotifyContinuations.STATION, "spotify:track:r")
            assertEquals(ContinuationKey("spotify-autoplay", "spotify:artist:a"), autoplay)
            assertEquals(ContinuationKey("spotify-station", "spotify:track:r"), station)
            assertEquals(autoplay, continuations.autoplay("spotify:artist:a").key)
            assertEquals(station, continuations.station("spotify:track:r").key)
            assertEquals(autoplay, continuations.of(autoplay)?.key)
            assertEquals(station, continuations.of(station)?.key)
            assertNull(continuations.of(ContinuationKey("spotify-future", "spotify:track:r")))
            assertNull(continuations.of(ContinuationKey(SpotifyContinuations.AUTOPLAY, "")))
            catalog.pages += ContextPage(listOf(spotify("r1")), next = "hm://station/page/2")
            continuations.of(station)?.more(emptyList())
            assertEquals(listOf("station spotify:track:r"), catalog.calls)
        }

    @Test
    fun `SPOT-11 a search pick has the best result's name and its autoplay, found tracks no name`() =
        runTest {
            val artist = SpotifyItem("spotify:artist:a", "Кино")
            catalog.pick = SpotifyPick(artist, "spotify:artist:a", listOf(spotify("t1")))
            val best = continuations.searchPick("кино")
            assertEquals("Кино", best.name)
            assertEquals(listOf("t1"), best.tracks.map(Track::id))
            assertEquals(autoplayOf("spotify:artist:a"), best.continuation?.key)
            catalog.pick = SpotifyPick(null, "spotify:track:t2", listOf(spotify("t2"), spotify("t3")))
            val found = continuations.searchPick("песня")
            assertEquals("", found.name)
            assertEquals(autoplayOf("spotify:track:t2"), found.continuation?.key)
            catalog.pick = SpotifyPick(null, "", emptyList())
            assertNull(continuations.searchPick("ничего").continuation)
            assertEquals(listOf("search кино", "search песня", "search ничего"), catalog.calls)
        }

    private fun spotify(id: String) = Track(id = id, title = "Spotify $id", source = TrackSource.SPOTIFY)

    private fun autoplayOf(contextUri: String) = ContinuationKey(SpotifyContinuations.AUTOPLAY, contextUri)

    private class FakeCatalog : SpotifyCatalog {
        val calls = ArrayList<String>()
        val pages = ArrayDeque<ContextPage>()
        var failure: Exception? = null
        var pick = SpotifyPick(null, "", emptyList())

        override suspend fun autoplay(contextUri: String, recentTrackUris: List<String>): ContextPage {
            calls += "autoplay $contextUri $recentTrackUris"
            return page()
        }

        override suspend fun station(trackUri: String): ContextPage {
            calls += "station $trackUri"
            return page()
        }

        override suspend fun nextPage(pageUrl: String): ContextPage {
            calls += "next $pageUrl"
            return page()
        }

        override suspend fun searchPick(text: String): SpotifyPick {
            calls += "search $text"
            return pick
        }

        private fun page(): ContextPage {
            failure?.let { throw it }
            return pages.removeFirst()
        }
    }
}
