package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.NetworkException
import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Track
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSourcesTest {
    @Test
    fun `SRC-01 only the source picked last is applied`() = runTest {
        val harness = QueueHarness(this)
        val slow = CompletableDeferred<List<Track>>()
        harness.controller.loadSource("A") { slow.await() }
        harness.controller.loadSource("B") { tracks("b1", "b2") }
        runCurrent()
        assertEquals(listOf("b1", "b2"), harness.engine.ids())
        slow.complete(tracks("a1"))
        runCurrent()
        assertEquals(listOf("b1", "b2"), harness.engine.ids())
        assertEquals(listOf(QueueEvent.SourceLoaded("B", 2)), harness.events)
    }

    @Test
    fun `SRC-02 a replaced source's failure is dropped silently`() = runTest {
        val harness = QueueHarness(this)
        val slow = CompletableDeferred<List<Track>>()
        harness.controller.loadSource("A") { slow.await() }
        harness.controller.loadSource("B") { tracks("b1") }
        runCurrent()
        slow.completeExceptionally(networkFailure())
        runCurrent()
        assertEquals(listOf(QueueEvent.SourceLoaded("B", 1)), harness.events)
    }

    @Test
    fun `SRC-03 a search waiting for its second request is replaced as one source`() = runTest {
        val harness = QueueHarness(this)
        val artistTracks = CompletableDeferred<List<Track>>()
        harness.source.onSearch = { SearchResult(bestType = "artist", bestId = "9", bestName = "Кино") }
        harness.source.onArtistTopTracks = { artistTracks.await() }
        harness.controller.search("кино", "Search: кино")
        runCurrent()
        harness.controller.loadSource("Liked") { tracks("l1") }
        runCurrent()
        artistTracks.complete(tracks("k1", "k2"))
        runCurrent()
        assertEquals(listOf("l1"), harness.engine.ids())
        assertEquals(
            listOf(QueueEvent.SearchStarted("Search: кино"), QueueEvent.SourceLoaded("Liked", 1)),
            harness.events,
        )
    }

    @Test
    fun `SRC-04 a failed source leaves the queue, shows the kind of error and logs the details`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1") }
        runCurrent()
        harness.controller.loadSource("New") { throw networkFailure() }
        runCurrent()
        assertEquals(listOf("o1"), harness.engine.ids())
        assertEquals(QueueEvent.Failed(Stage.SOURCE, ErrorKind.NoNetwork), harness.events.last())
        assertEquals(listOf("SOURCE GET /x failed: offline"), harness.logged)
    }

    @Test
    fun `SRC-05 unavailable tracks are left out of the queue`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Mixed") {
            listOf(track("a"), track("b", available = false), track("c"))
        }
        runCurrent()
        assertEquals(listOf("a", "c"), harness.engine.ids())
        assertEquals(listOf("a", "c"), harness.controller.state.value.tracks.map { it.id })
    }

    @Test
    fun `SRC-06 a source replaces the queue, plays the first track and reports its count`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked", sourceId = "liked") { tracks("a", "b", "c") }
        runCurrent()
        assertEquals("set [a, b, c] play=true", harness.engine.commands.last { it.startsWith("set") })
        val state = harness.controller.state.value
        assertEquals("Liked", state.title)
        assertEquals("liked", state.activeSourceId)
        assertEquals(false, state.isWave)
        assertEquals(listOf(QueueEvent.SourceLoaded("Liked", 3)), harness.events)
    }

    @Test
    fun `SRC-07 an empty source leaves the queue as it was`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1") }
        runCurrent()
        harness.controller.loadSource("Empty") { emptyList() }
        runCurrent()
        assertEquals(listOf("o1"), harness.engine.ids())
        assertEquals(QueueEvent.SourceEmpty("Empty"), harness.events.last())
    }

    @Test
    fun `SRC-08 a source of only unavailable tracks leaves the queue and is reported empty`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1") }
        runCurrent()
        harness.engine.commands.clear()
        harness.controller.loadSource("Gone") { listOf(track("x", available = false)) }
        runCurrent()
        assertEquals(listOf("o1"), harness.engine.ids())
        assertEquals(emptyList<String>(), harness.engine.commands)
        assertEquals("Old", harness.controller.state.value.title)
        assertEquals(QueueEvent.SourceEmpty("Gone"), harness.events.last())
    }

    @Test
    fun `SRC-08 SRC-12 a search whose tracks are all unavailable finds nothing`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1") }
        runCurrent()
        harness.source.onSearch = {
            SearchResult(tracks = listOf(track("x", available = false)))
        }
        harness.controller.search("кино", "Search: кино")
        runCurrent()
        assertEquals(listOf("o1"), harness.engine.ids())
        assertEquals(QueueEvent.NothingFound, harness.events.last())
    }

    @Test
    fun `SRC-09 a best artist queues its top tracks under the artist's name`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onSearch =
            { SearchResult(bestType = "artist", bestId = "9", bestName = "Кино", tracks = tracks("s1")) }
        harness.source.onArtistTopTracks = { tracks("k1", "k2") }
        harness.controller.search("кино", "Search: кино")
        runCurrent()
        assertEquals(listOf("search кино", "artistTopTracks 9"), harness.source.calls)
        assertEquals(listOf("k1", "k2"), harness.engine.ids())
        assertEquals("Кино", harness.controller.state.value.title)
    }

    @Test
    fun `SRC-10 a best album queues its tracks under the album's title`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onSearch =
            { SearchResult(bestType = "album", bestId = "4053", bestName = "Звезда") }
        harness.source.onAlbumTracks = { tracks("z1", "z2") }
        harness.controller.search("звезда", "Search: звезда")
        runCurrent()
        assertEquals(listOf("z1", "z2"), harness.engine.ids())
        assertEquals("Звезда", harness.controller.state.value.title)
    }

    @Test
    fun `SRC-11 any other best result queues the found tracks under the search title`() = runTest {
        val harness = QueueHarness(this)
        for (type in listOf("track", "playlist", "other", "")) {
            harness.source.onSearch =
                {
                    SearchResult(
                        bestType = type,
                        bestId = "1",
                        bestName = "X",
                        tracks = tracks("s1", "s2"),
                    )
                }
            harness.controller.search("кино", "Search: кино")
            runCurrent()
            assertEquals(type, listOf("s1", "s2"), harness.engine.ids())
            assertEquals(type, "Search: кино", harness.controller.state.value.title)
        }
        harness.source.onSearch =
            { SearchResult(bestType = "artist", bestId = "", tracks = tracks("s3")) }
        harness.controller.search("кино", "Search: кино")
        runCurrent()
        assertEquals(listOf("s3"), harness.engine.ids())
    }

    @Test
    fun `SRC-12 an empty result keeps the queue and says nothing was found`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1") }
        runCurrent()
        harness.source.onSearch =
            { SearchResult(bestType = "artist", bestId = "9", tracks = tracks("s1")) }
        harness.source.onArtistTopTracks = { emptyList() }
        harness.controller.search("кино", "Search: кино")
        runCurrent()
        assertEquals(listOf("o1"), harness.engine.ids())
        assertEquals(QueueEvent.NothingFound, harness.events.last())
        assertTrue(
            "no fallback to tracks.results",
            harness.source.calls.none {
                it.startsWith("albumTracks")
            },
        )
    }

    private fun networkFailure() = NetworkException("GET", "/x", IOException("offline"))
}
