package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.FakeContinuation
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import java.net.ConnectException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueAddTest {
    @Test
    fun `ADD-01 Play next puts the tracks behind the current one, in front of those that wait`() = runTest {
        val h = playing(this, "a", "b", "c")
        h.controller.add(tracks("x", "y"), "Search: x", AddPlacement.NEXT)
        assertEquals(listOf("a", "x", "y", "b", "c"), h.engine.ids())
        h.controller.add(tracks("z"), "Search: z", AddPlacement.NEXT)
        assertEquals(listOf("a", "z", "x", "y", "b", "c"), h.engine.ids())
        assertEquals(listOf(null, true, true, true, null, null), h.waiting())
        assertEquals("a", h.currentId())
        assertFalse(h.engine.commands.contains("play"))
        runCurrent()
        assertEquals(
            listOf(
                QueueEvent.Added("Search: x", 2, AddPlacement.NEXT),
                QueueEvent.Added("Tz", 1, AddPlacement.NEXT),
            ),
            h.events,
        )
        assertEquals(listOf("z", "x", "y", "b", "c"), h.playOn(5))
    }

    @Test
    fun `ADD-02 waiting tracks play once, shuffle leaves them out and repeat does not play them again`() =
        runTest {
            val h = playing(this, "a", "b", "c")
            h.controller.setShuffle(true)
            h.controller.setRepeat(true)
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            assertEquals(listOf("x", "b", "c", "a", "b", "c", "a"), h.playOn(7))
            assertEquals(listOf("a", "x", "b", "c"), h.engine.ids())
            assertEquals(false, h.controller.state.value.entries[1].item?.waiting)
        }

    @Test
    fun `ADD-02 picking another track moves the waiting tracks right behind it`() = runTest {
        val h = playing(this, "a", "b", "c", "d")
        h.controller.add(tracks("x", "y"), "Search", AddPlacement.NEXT)
        h.engine.seekTo(4)
        assertEquals(listOf("a", "b", "c", "x", "y", "d"), h.engine.ids())
        assertEquals("c", h.currentId())
        assertEquals(listOf("x", "y", "d"), h.playOn(3))
    }

    @Test
    fun `ADD-02 Previous from a head track passes a played waiting track, from it goes to the row above`() =
        runTest {
            val h = playing(this, "a", "b", "c")
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            assertEquals(listOf("x", "b"), h.playOn(2))
            h.controller.previous()
            assertEquals("a", h.currentId())
            h.engine.seekTo(1)
            assertEquals("x", h.currentId())
            h.controller.previous()
            assertEquals("a", h.currentId())
        }

    @Test
    fun `ADD-02 Previous on the first head track with a played waiting track last goes to the head's last`() =
        runTest {
            val h = playing(this, "a", "b")
            h.controller.setRepeat(true)
            h.engine.seekTo(1)
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            assertEquals(listOf("x", "a"), h.playOn(2))
            assertEquals(listOf("a", "b", "x"), h.engine.ids())
            h.controller.previous()
            assertEquals("b", h.currentId())
        }

    @Test
    fun `ADD-03 Add to the end in the head appends to the head, in front of the continuation`() = runTest {
        val autoplay = FakeContinuation()
        val h = QueueHarness(this, continueWithVibe = true)
        h.controller.loadSource("Liked Songs", continuation = autoplay) { tracks("a", "b", "c") }
        runCurrent()
        autoplay.replies(tracks("w1", "w2"))
        h.engine.startPlaying()
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("a", "b", "c", "w1", "w2"), h.engine.ids())
        h.controller.add(tracks("x", "y"), "Album", AddPlacement.END)
        assertEquals(listOf("a", "b", "c", "x", "y", "w1", "w2"), h.engine.ids())
        assertEquals(
            List(5) { EntryPart.HEAD } + List(2) { EntryPart.CONTINUATION },
            h.controller.state.value.entries.map { it.part },
        )
        assertEquals("b", h.currentId())
        runCurrent()
        assertEquals(QueueEvent.Added("Album", 2, AddPlacement.END), h.events.last())
        assertEquals(listOf("c", "x", "y", "w1"), h.playOn(4))
    }

    @Test
    fun `ADD-03 the added tracks go behind every item row after the head, a played one too`() = runTest {
        val h = playing(this, "a", "b")
        h.engine.seekTo(1)
        h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
        assertEquals(listOf("x"), h.playOn(1))
        h.controller.add(tracks("y"), "Search", AddPlacement.END)
        assertEquals(listOf("a", "b", "x", "y"), h.engine.ids())
        assertEquals(EntryPart.HEAD, h.controller.state.value.entries[3].part)
        assertEquals(listOf("y"), h.playOn(1))
    }

    @Test
    fun `ADD-03 with Without repeats each added track joins the pass among the tracks still to play`() =
        runTest {
            val h = playing(this, "a", "b", "c", "d")
            h.controller.setShuffle(true)
            h.engine.finishTrack()
            assertEquals(listOf(0, 1, 2, 3), h.engine.playOrder())
            h.controller.add(tracks("x", "y"), "Album", AddPlacement.END)
            val order = h.engine.playOrder()
            val played = order.indexOf(1)
            assertEquals(listOf(0, 1), order.take(2))
            assertTrue(order.indexOf(4) > played)
            assertTrue(order.indexOf(5) > played)
            assertEquals((0..5).toSet(), order.toSet())
            assertEquals("insert head 4 [x, y] after 0", h.engine.commands.last { it.startsWith("insert") })
            assertEquals(setOf("c", "d", "x", "y"), h.playOn(4).toSet())
        }

    @Test
    fun `ADD-03 with Without repeats a track added after a waiting one goes behind the waiting one`() =
        runTest {
            val h = playing(this, "a", "b", "c")
            h.controller.setShuffle(true)
            h.controller.add(tracks("w"), "Search", AddPlacement.NEXT)
            h.controller.add(tracks("x"), "Album", AddPlacement.END)
            assertEquals("insert head 4 [x] after 1", h.engine.commands.last { it.startsWith("insert") })
            assertEquals(listOf(0, 1), h.engine.playOrder().take(2))
        }

    @Test
    fun `ADD-03 SHUF-05 with Random track a draw made stands, and the next draw may pick the added`() =
        runTest {
            val h = playing(this, "a", "b", "c")
            h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
            h.controller.setShuffle(true)
            val drawn = h.engine.playOrder()[1]
            h.controller.add(tracks("x"), "Album", AddPlacement.END)
            assertEquals("insert head 3 [x] after 1", h.engine.commands.last { it.startsWith("insert") })
            assertEquals(drawn, h.engine.playOrder()[1])
            h.engine.finishTrack()
            assertEquals(h.engine.tracks[drawn].id, h.currentId())
        }

    @Test
    fun `ADD-03 a one-track head with an added track starts to draw at random`() = runTest {
        val h = playing(this, "a")
        h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
        h.controller.setShuffle(true)
        h.controller.add(tracks("x"), "Album", AddPlacement.END)
        assertEquals(listOf("x", "a", "x"), h.playOn(3))
    }

    @Test
    fun `ADD-04 in the continuation Add to the end waits behind the waiting tracks`() = runTest {
        val h = QueueHarness.wave(this, "w1", "w2", "w3")
        h.engine.startPlaying()
        h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
        h.controller.add(tracks("y"), "Search", AddPlacement.END)
        h.controller.add(tracks("z"), "Search", AddPlacement.NEXT)
        assertEquals(listOf("w1", "z", "x", "y", "w2", "w3"), h.engine.ids())
        assertEquals(listOf(null, true, true, true, null, null), h.waiting())
        assertEquals(listOf("z", "x", "y", "w2"), h.playOn(4))
    }

    @Test
    fun `ADD-04 with the head emptied Add to the end makes waiting tracks`() = runTest {
        val h = playing(this, "a")
        h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
        h.controller.removeIndices(setOf(0))
        h.controller.add(tracks("y"), "Search", AddPlacement.END)
        assertEquals(listOf("x", "y"), h.engine.ids())
        assertEquals(listOf(EntryPart.ITEM, EntryPart.ITEM), h.controller.state.value.entries.map { it.part })
    }

    @Test
    fun `ADD-05 into an empty queue the tracks become a new head under the title, nothing starts`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.controller.add(
                listOf(track("x", available = false), track("y"), track("z")),
                "Альбом",
                AddPlacement.END,
            )
            assertEquals(listOf("y", "z"), h.engine.ids())
            assertEquals("Альбом", h.controller.state.value.title)
            assertEquals(
                listOf(EntryPart.HEAD, EntryPart.HEAD),
                h.controller.state.value.entries.map {
                    it.part
                },
            )
            assertTrue(h.controller.state.value.continueWithVibe)
            assertEquals(0, h.engine.currentIndex)
            assertFalse(h.engine.playWhenReady)
            assertEquals(listOf("set [y, z] play=false"), h.engine.commands.filter { it.startsWith("set") })
            runCurrent()
            assertEquals(listOf(QueueEvent.Added("Альбом", 2, AddPlacement.END)), h.events)
        }

    @Test
    fun `ADD-05 Spotify tracks get Spotify's autoplay from the first of them`() = runTest {
        val autoplay = FakeContinuation(ContinuationKey("spotify-autoplay", "spotify:track:s1"))
        val from = ArrayList<String>()
        val h =
            QueueHarness(this, continueWithVibe = true, autoplayFrom = {
                from += it.id
                autoplay.takeIf { _ -> it.source == TrackSource.SPOTIFY }
            })
        h.controller.add(
            listOf(spotify("s0", available = false), spotify("s1"), spotify("s2")),
            "Album",
            AddPlacement.NEXT,
        )
        assertEquals(listOf("s1"), from)
        autoplay.replies(listOf(spotify("p1")))
        h.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("s1", "s2", "p1"), h.engine.ids())
    }

    @Test
    fun `ADD-05 an add with no available track changes nothing`() = runTest {
        val h = playing(this, "a")
        h.engine.commands.clear()
        h.controller.add(listOf(track("x", available = false)), "Search", AddPlacement.NEXT)
        h.controller.add(emptyList(), "Search", AddPlacement.END)
        assertEquals(listOf("a"), h.engine.ids())
        assertEquals(emptyList<String>(), h.engine.commands)
        runCurrent()
        assertEquals(emptyList<QueueEvent>(), h.events)
        val empty = QueueHarness(this)
        empty.controller.add(listOf(track("x", available = false)), "Search", AddPlacement.END)
        assertTrue(empty.controller.state.value.entries.isEmpty())
        runCurrent()
        assertEquals(emptyList<QueueEvent>(), empty.events)
    }

    @Test
    fun `ADD-05 an add is no source request, a source reply that comes later replaces the queue`() = runTest {
        val h = playing(this, "a")
        val reply = CompletableDeferred<List<Track>>()
        h.controller.loadSource("Playlist") { reply.await() }
        runCurrent()
        h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
        assertEquals(listOf("a", "x"), h.engine.ids())
        reply.complete(tracks("p1", "p2"))
        runCurrent()
        assertEquals(listOf("p1", "p2"), h.engine.ids())
    }

    @Test
    fun `ADD-06 in a queue stopped at its end nothing starts, Next plays the first added`() = runTest {
        val h = playing(this, "a", "b")
        h.playOn(1)
        h.engine.finishTrack()
        assertTrue(h.engine.isEnded)
        h.engine.commands.clear()
        h.controller.add(tracks("x", "y"), "Search", AddPlacement.END)
        assertEquals(emptyList<String>(), h.engine.commands.filter { it == "play" || it.startsWith("seek") })
        h.controller.next()
        assertEquals("x", h.currentId())
        assertTrue(h.engine.playWhenReady)
    }

    @Test
    fun `ADD-07 a removed waiting track does not play`() = runTest {
        val h = playing(this, "a", "b")
        h.controller.add(tracks("x", "y"), "Search", AddPlacement.NEXT)
        h.controller.removeIndices(setOf(1))
        assertEquals(listOf("y", "b"), h.playOn(2))
    }

    @Test
    fun `ADD-07 removing the current track stops playback, and the waiting track behind it waits for Play`() =
        runTest {
            val h = playing(this, "a", "b")
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            h.controller.removeIndices(setOf(0))
            assertEquals("x", h.currentId())
            assertFalse(h.engine.playWhenReady)
            assertEquals(true, h.controller.state.value.entries[0].item?.waiting)
            h.engine.play()
            h.engine.startPlaying()
            assertEquals(false, h.controller.state.value.entries[0].item?.waiting)
            assertEquals(listOf("b"), h.playOn(1))
        }

    @Test
    fun `ADD-07 the held waiting track plays on Next, and Play next while held goes in front of it`() =
        runTest {
            val h = playing(this, "a", "b")
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            h.controller.removeIndices(setOf(0))
            h.engine.commands.clear()
            h.controller.add(tracks("y"), "Search", AddPlacement.NEXT)
            assertEquals(listOf("y", "x", "b"), h.engine.ids())
            assertEquals("y", h.currentId())
            assertEquals(true, h.controller.state.value.entries[0].item?.waiting)
            assertFalse(h.engine.commands.contains("play"))
            h.controller.next()
            assertTrue(h.engine.playWhenReady)
            assertEquals("y", h.currentId())
            assertEquals(false, h.controller.state.value.entries[0].item?.waiting)
            h.engine.startPlaying()
            assertEquals(listOf("x", "b"), h.playOn(2))
        }

    @Test
    fun `ADD-07 SHUF-01 with shuffle, Play next while held moves the hold and keeps the pass's order`() =
        runTest {
            val h = playing(this, "a", "b", "c", "d")
            h.controller.setShuffle(true)
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            h.controller.removeIndices(setOf(0))
            assertEquals("x", h.currentId())
            val order = h.engine.playOrder().map { h.engine.tracks[it].id }
            h.controller.add(tracks("y"), "Search", AddPlacement.NEXT)
            assertEquals("y", h.currentId())
            assertEquals(listOf("y") + order, h.engine.playOrder().map { h.engine.tracks[it].id })
            h.controller.next()
            h.engine.startPlaying()
            assertEquals(listOf("x", "b", "c", "d"), h.playOn(4))
        }

    @Test
    fun `ADD-07 a held waiting track survives the process, Play next before a player holds the added`() =
        runTest {
            val h = playing(this, "a", "b")
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            h.controller.removeIndices(setOf(0))
            runCurrent()
            val restored = QueueHarness(this, attached = false, store = FakeQueueStore(h.store.text))
            restored.controller.add(tracks("y"), "Search", AddPlacement.NEXT)
            restored.controller.attach(restored.engine)
            assertEquals(listOf("y", "x", "b"), restored.engine.ids())
            assertEquals("y", restored.currentId())
            assertFalse(restored.engine.playWhenReady)
            assertEquals(listOf(true, true, null), restored.waiting())
            restored.engine.play()
            restored.engine.startPlaying()
            assertEquals(listOf(false, true, null), restored.waiting())
            assertEquals(listOf("x", "b"), restored.playOn(2))
        }

    @Test
    fun `ADD-02 SHUF-05 with Random track a draw made before Play next stands after the waiting track`() =
        runTest {
            val h = playing(this, "a", "b", "c", "d")
            h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
            h.controller.setShuffle(true)
            val drawn = h.engine.tracks[h.engine.playOrder()[1]].id
            h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
            assertEquals(listOf("x", drawn), h.playOn(2))
        }

    @Test
    fun `ADD-07 D8 removing the current track with a head track behind it still plays on`() = runTest {
        val h = playing(this, "a", "b", "c")
        h.controller.add(tracks("y"), "Search", AddPlacement.END)
        h.controller.removeIndices(setOf(0))
        assertEquals("b", h.currentId())
        assertTrue(h.engine.playing)
    }

    @Test
    fun `ADD-08 a queue waiting for its continuation plays the first added track at once`() = runTest {
        val autoplay = FakeContinuation()
        val reply = CompletableDeferred<List<Track>>()
        autoplay.replies(then = reply)
        val h = QueueHarness(this, continueWithVibe = true)
        h.controller.loadSource("Liked Songs", continuation = autoplay) { tracks("a") }
        runCurrent()
        h.engine.startPlaying()
        h.engine.finishTrack()
        runCurrent()
        assertTrue(h.controller.state.value.loadingMore)
        h.engine.commands.clear()
        h.controller.add(tracks("x"), "Search", AddPlacement.END)
        assertEquals("x", h.currentId())
        assertEquals(
            listOf("insert head 1 [x] after 0", "seek 1", "prepare", "play"),
            h.engine.commands.withoutModes(),
        )
        assertFalse(h.controller.state.value.loadingMore)
        reply.complete(tracks("w1"))
        runCurrent()
        assertEquals(listOf("a", "x", "w1"), h.engine.ids())
        assertEquals("x", h.currentId())
    }

    @Test
    fun `ADD-02 ADD-09 waiting tracks survive the process, still waiting`() = runTest {
        val h = playing(this, "a", "b")
        h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
        runCurrent()
        val restored = QueueHarness(this, store = FakeQueueStore(h.store.text))
        assertEquals(listOf("a", "x", "b"), restored.engine.ids())
        assertEquals(listOf(null, true, null), restored.waiting())
        restored.engine.startPlaying()
        assertEquals(listOf("x", "b"), restored.playOn(2))
    }

    @Test
    fun `ADD-03 CONT-05 a Spotify track added to a Yandex queue is no seed of the vibe after it`() = runTest {
        val h = QueueHarness(this, continueWithVibe = true)
        h.controller.setQueue(tracks("a"), "Likes", isWave = false, autoplay = true, sourceId = null)
        h.engine.startPlaying()
        h.controller.add(listOf(spotify("s1")), "Search", AddPlacement.END)
        h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1")) }
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("startWave [track:a]"), h.source.calls.filter { it.startsWith("startWave") })
    }

    @Test
    fun `SRC-18 a found track plays the found tracks from it, the tracks before it stay`() = runTest {
        val h = QueueHarness(this)
        val found = listOf(track("a"), track("u", available = false), track("b"), track("c"))
        h.controller.playTracks("Search: x", found, start = 2)
        assertEquals(listOf("a", "b", "c"), h.engine.ids())
        assertEquals("b", h.currentId())
        assertTrue(h.engine.playWhenReady)
        assertEquals("Search: x", h.controller.state.value.title)
        runCurrent()
        assertEquals(listOf(QueueEvent.SourceLoaded("Search: x", 3)), h.events)
        assertEquals(listOf("c"), h.playOn(1))
    }

    @Test
    fun `SRC-18 SHUF-01 with shuffle on the pass begins with the chosen track`() = runTest {
        val h = QueueHarness(this)
        h.controller.setShuffle(true)
        h.controller.playTracks("Search: x", tracks("a", "b", "c", "d"), start = 2)
        assertEquals(2, h.engine.playOrder().first())
        assertEquals(listOf(2), h.engine.leads.last())
        assertEquals("c", h.currentId())
    }

    @Test
    fun `SRC-16 an opened album's track plays under its title, unavailable start falls back to the first`() =
        runTest {
            val autoplay = FakeContinuation()
            val h = QueueHarness(this)
            h.controller.playTracks(
                "Альбом",
                listOf(track("a"), track("u", available = false)),
                start = 1,
                sourceId = "album:9",
                continuation = autoplay,
            )
            assertEquals("a", h.currentId())
            assertEquals("album:9", h.controller.state.value.activeSourceId)
            h.controller.playTracks("Пусто", listOf(track("u", available = false)), start = 0)
            runCurrent()
            assertEquals(QueueEvent.SourceEmpty("Пусто"), h.events.last())
            assertEquals(listOf("a"), h.engine.ids())
        }

    @Test
    fun `SRC-01 playing found tracks drops a source request in flight`() = runTest {
        val h = QueueHarness(this)
        val reply = CompletableDeferred<List<Track>>()
        h.controller.loadSource("Playlist") { reply.await() }
        runCurrent()
        h.controller.playTracks("Search: x", tracks("a"), start = 0)
        reply.complete(tracks("p1"))
        runCurrent()
        assertEquals(listOf("a"), h.engine.ids())
    }

    @Test
    fun `SRC-17 an album is added when its tracks arrive, a failure and an empty album say so`() = runTest {
        val h = playing(this, "a")
        val album = CompletableDeferred<List<Track>>()
        h.controller.addLoaded("Альбом", AddPlacement.NEXT) { album.await() }
        h.controller.add(tracks("x"), "Search", AddPlacement.NEXT)
        album.complete(tracks("p1", "p2"))
        runCurrent()
        assertEquals(listOf("a", "p1", "p2", "x"), h.engine.ids())
        runCurrent()
        assertEquals(QueueEvent.Added("Альбом", 2, AddPlacement.NEXT), h.events.last())
        h.controller.addLoaded("Сломан", AddPlacement.END) { throw ConnectException("down") }
        runCurrent()
        assertEquals(QueueEvent.Failed(Stage.SOURCE, ErrorKind.NoNetwork), h.events.last())
        h.controller.addLoaded("Пусто", AddPlacement.END) { listOf(track("u", available = false)) }
        runCurrent()
        assertEquals(QueueEvent.SourceEmpty("Пусто"), h.events.last())
        assertEquals(listOf("a", "p1", "p2", "x"), h.engine.ids())
    }

    @Test
    fun `SRC-17 ADD-05 an album added to an empty queue gets autoplay from its first available track`() =
        runTest {
            val from = ArrayList<String>()
            val h = QueueHarness(this, autoplayFrom = {
                from += it.id
                null
            })
            h.controller.addLoaded("Album", AddPlacement.END) {
                listOf(spotify("s0", available = false), spotify("s1"), spotify("s2"))
            }
            runCurrent()
            assertEquals(listOf("s1"), from)
            assertEquals(listOf("s1", "s2"), h.engine.ids())
            assertNull(h.controller.state.value.activeSourceId)
        }

    private fun playing(scope: TestScope, vararg ids: String): QueueHarness {
        val h = QueueHarness.likes(scope, *ids, continueWithVibe = false)
        h.engine.startPlaying()
        scope.runCurrent()
        h.events.clear()
        h.engine.commands.clear()
        return h
    }

    private fun QueueHarness.waiting(): List<Boolean?> = controller.state.value.entries.map {
        it.item?.waiting
    }

    private fun QueueHarness.currentId(): String = engine.tracks[engine.currentIndex].id

    private fun QueueHarness.playOn(count: Int): List<String> = (1..count).map {
        engine.finishTrack()
        currentId()
    }

    private fun List<String>.withoutModes(): List<String> =
        filterNot { it.startsWith("pause at end") || it.startsWith("shuffle") || it.startsWith("repeat") }

    private fun spotify(id: String, available: Boolean = true) =
        Track(id = id, title = "Spotify $id", available = available, source = TrackSource.SPOTIFY)
}
