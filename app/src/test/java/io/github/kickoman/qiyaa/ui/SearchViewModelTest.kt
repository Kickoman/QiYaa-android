package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.jam.Decoded
import io.github.kickoman.qiyaa.jam.JamCodec
import io.github.kickoman.qiyaa.jam.JamParticipant
import io.github.kickoman.qiyaa.jam.JamQueueItem
import io.github.kickoman.qiyaa.jam.JamRoom
import io.github.kickoman.qiyaa.jam.JamTrack
import io.github.kickoman.qiyaa.jam.ParticipantKind
import io.github.kickoman.qiyaa.jam.State
import io.github.kickoman.qiyaa.playback.JamHostEvent
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.playback.JamHostState
import io.github.kickoman.qiyaa.queue.AddPlacement
import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.ContinuationKey
import io.github.kickoman.qiyaa.queue.QueueEvent
import io.github.kickoman.qiyaa.support.FakeContinuation
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.Spec
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.ErrorKind
import java.io.File
import java.net.ConnectException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val gateway = FakeGateway()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `SRC-13 a search shows what it found and queues nothing, an empty text sends nothing`() = runTest {
        val subject = Subject(this)
        gateway.found = { found }
        subject.model.open("   ", SearchSource.YANDEX)
        runCurrent()
        assertEquals(emptyList<String>(), gateway.calls)
        subject.model.open("  кино ", SearchSource.YANDEX)
        assertEquals(SearchPhase.Searching, subject.model.ui.value.phase)
        runCurrent()
        assertEquals(listOf("find YANDEX кино"), gateway.calls)
        assertEquals(SearchPhase.Results(found), subject.model.ui.value.phase)
        assertEquals("кино", subject.model.ui.value.query)
        assertTrue(subject.harness.controller.state.value.entries.isEmpty())
        assertEquals(emptyList<QueueEvent>(), subject.harness.events)
    }

    @Test
    fun `SRC-14 a newer search drops the older reply, and a source request is not dropped by it`() = runTest {
        val subject = Subject(this)
        val older = CompletableDeferred<Found>()
        gateway.found = { text -> if (text == "old") older.await() else found }
        val playlist = CompletableDeferred<List<Track>>()
        subject.harness.controller.loadSource("Playlist") { playlist.await() }
        subject.model.open("old", SearchSource.YANDEX)
        runCurrent()
        subject.model.setText("new")
        subject.model.search()
        runCurrent()
        older.complete(Found(tracks = listOf(track("late"))))
        playlist.complete(listOf(track("p1")))
        runCurrent()
        assertEquals(SearchPhase.Results(found), subject.model.ui.value.phase)
        assertEquals(listOf("p1"), subject.harness.engine.ids())
    }

    @Test
    fun `SRC-14 another source searches the same text, one that cannot search shows its login`() = runTest {
        val subject = Subject(this)
        gateway.found = { found }
        subject.model.open("кино", SearchSource.YANDEX)
        runCurrent()
        gateway.allowed = setOf(SearchSource.YANDEX)
        subject.model.setSource(SearchSource.SPOTIFY)
        runCurrent()
        assertEquals(SearchPhase.Idle, subject.model.ui.value.phase)
        assertEquals(listOf("find YANDEX кино"), gateway.calls)
        gateway.allowed = SearchSource.entries.toSet()
        subject.model.retry()
        runCurrent()
        assertEquals(listOf("find YANDEX кино", "find SPOTIFY кино"), gateway.calls)
    }

    @Test
    fun `SRC-15 a failed search shows the failure with a way to search again, the queue stays`() = runTest {
        val subject = Subject(this)
        gateway.found = { throw ConnectException("offline") }
        subject.model.open("кино", SearchSource.YANDEX)
        runCurrent()
        assertEquals(SearchPhase.Failed(ErrorKind.NoNetwork), subject.model.ui.value.phase)
        assertEquals(emptyList<QueueEvent>(), subject.harness.events)
        gateway.found = { found }
        subject.model.retry()
        runCurrent()
        assertEquals(SearchPhase.Results(found), subject.model.ui.value.phase)
    }

    @Test
    fun `SRC-19 a reply with nothing to show says so`() = runTest {
        val subject = Subject(this)
        gateway.found = { Found() }
        subject.model.open("zzz", SearchSource.YANDEX)
        runCurrent()
        assertEquals(SearchPhase.NothingFound, subject.model.ui.value.phase)
    }

    @Test
    fun `SRC-18 a found track plays the found tracks from it under the search's title`() = runTest {
        val subject = Subject(this)
        gateway.found = { found }
        subject.model.open("кино", SearchSource.YANDEX)
        runCurrent()
        subject.model.play(track("t2"))
        runCurrent()
        assertEquals(listOf("best", "t1", "t2"), subject.harness.engine.ids())
        assertEquals("t2", subject.currentId())
        assertEquals("queue_search_title(кино)", subject.harness.controller.state.value.title)
        assertEquals(SearchPhase.Results(found), subject.model.ui.value.phase)
    }

    @Test
    fun `SRC-18 SPOT-20 found Spotify tracks get autoplay from the queue's first track`() = runTest {
        val subject = Subject(this)
        gateway.found = { Found(tracks = listOf(spotify("s1"), spotify("s2"))) }
        subject.model.open("кино", SearchSource.SPOTIFY)
        runCurrent()
        subject.model.play(spotify("s2"))
        runCurrent()
        assertEquals(listOf("autoplay spotify:track:s1"), gateway.calls.filter { it.startsWith("autoplay") })
        assertEquals("s2", subject.currentId())
    }

    @Test
    fun `SRC-16 an opened artist shows its available tracks, Back shows the results with no request`() =
        runTest {
            val subject = Subject(this)
            gateway.found = { found }
            gateway.tracks = { listOf(track("k1"), track("u", available = false), track("k2")) }
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            subject.model.toggle(SearchPart.TRACKS)
            subject.model.openPick(artist)
            assertEquals(SearchOpened(artist), subject.model.ui.value.opened)
            runCurrent()
            assertEquals(
                SearchOpened(artist, ListState.Loaded(listOf(track("k1"), track("k2")))),
                subject.model.ui.value.opened,
            )
            assertTrue(subject.model.back())
            assertNull(subject.model.ui.value.opened)
            assertEquals(SearchPhase.Results(found), subject.model.ui.value.phase)
            assertEquals(setOf(SearchPart.TRACKS), subject.model.ui.value.expanded)
            assertEquals(listOf("find YANDEX кино", "tracks YANDEX ARTIST 9"), gateway.calls)
            assertEquals(false, subject.model.back())
        }

    @Test
    fun `SRC-16 a newer open, Back or a new search drops an older open's reply, a failure is shown`() =
        runTest {
            val subject = Subject(this)
            val slow = CompletableDeferred<List<Track>>()
            gateway.found = { found }
            gateway.tracks =
                { pick -> if (pick == artist) slow.await() else throw ConnectException("offline") }
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            subject.model.openPick(artist)
            runCurrent()
            subject.model.openPick(album)
            runCurrent()
            slow.complete(listOf(track("late")))
            runCurrent()
            assertEquals(
                SearchOpened(album, ListState.Failed(ErrorKind.NoNetwork)),
                subject.model.ui.value.opened,
            )
            subject.model.back()
            subject.model.search()
            runCurrent()
            assertNull(subject.model.ui.value.opened)
        }

    @Test
    fun `SRC-16 an open's reply after Back or after a new search is dropped`() = runTest {
        val subject = Subject(this)
        val slow = CompletableDeferred<List<Track>>()
        gateway.found = { found }
        gateway.tracks = { slow.await() }
        subject.model.open("кино", SearchSource.YANDEX)
        runCurrent()
        subject.model.openPick(artist)
        runCurrent()
        assertTrue(subject.model.back())
        slow.complete(listOf(track("late")))
        runCurrent()
        assertNull(subject.model.ui.value.opened)
        val slower = CompletableDeferred<List<Track>>()
        gateway.tracks = { slower.await() }
        subject.model.openPick(album)
        runCurrent()
        subject.model.search()
        slower.complete(listOf(track("late")))
        runCurrent()
        assertNull(subject.model.ui.value.opened)
        assertEquals(SearchPhase.Results(found), subject.model.ui.value.phase)
    }

    @Test
    fun `SRC-16 a track of an opened album plays the album from it under its title`() = runTest {
        val subject = Subject(this)
        gateway.found = { found }
        gateway.tracks = { listOf(track("a1"), track("a2"), track("a3")) }
        subject.model.open("кино", SearchSource.YANDEX)
        runCurrent()
        subject.model.openPick(album)
        runCurrent()
        subject.model.play(track("a2"))
        runCurrent()
        val queue = subject.harness.controller.state.value
        assertEquals("Группа крови", queue.title)
        assertEquals("album:4", queue.activeSourceId)
        assertEquals("a2", subject.currentId())
        subject.model.playAll()
        assertEquals("a1", subject.currentId())
    }

    @Test
    fun `SRC-09 SRC-10 SPOT-09 an artist or album played from the results is a source request`() = runTest {
        val subject = Subject(this)
        gateway.found = { found }
        gateway.tracks = { listOf(track("k1")) }
        subject.model.open("кино", SearchSource.YANDEX)
        runCurrent()
        subject.model.playPick(artist)
        runCurrent()
        assertEquals("artist:9", subject.harness.controller.state.value.activeSourceId)
        assertEquals("Кино", subject.harness.controller.state.value.title)
        val spotifyAlbum = FoundPick(FoundPick.Kind.ALBUM, "spotify:album:x", "Double")
        subject.model.setSource(SearchSource.SPOTIFY)
        runCurrent()
        subject.model.playPick(spotifyAlbum)
        runCurrent()
        assertEquals("spotify:album:x", subject.harness.controller.state.value.activeSourceId)
        assertTrue(gateway.calls.contains("autoplay spotify:album:x"))
    }

    @Test
    fun `SRC-17 Play next and Add to the end mark a track's row, an album is loaded or taken as opened`() =
        runTest {
            val subject = Subject(this)
            gateway.found = { found }
            gateway.tracks = { listOf(track("a1"), track("a2")) }
            subject.harness.controller.setQueue(
                listOf(track("q")),
                "Q",
                isWave = false,
                autoplay = true,
                null,
            )
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            subject.model.chooseAdd(SearchTarget.OfTrack(track("t1")))
            assertEquals(SearchTarget.OfTrack(track("t1")), subject.model.ui.value.adding)
            subject.model.add(AddPlacement.NEXT)
            assertNull(subject.model.ui.value.adding)
            assertEquals(mapOf(track("t1").key to AddPlacement.NEXT), subject.model.ui.value.added)
            assertEquals(listOf("q", "t1"), subject.harness.engine.ids())
            subject.model.chooseAdd(SearchTarget.OfAlbum(album))
            subject.model.add(AddPlacement.END)
            runCurrent()
            assertEquals(listOf("q", "t1", "a1", "a2"), subject.harness.engine.ids())
            assertEquals("tracks YANDEX ALBUM 4", gateway.calls.last())
            subject.model.openPick(album)
            runCurrent()
            val calls = gateway.calls.size
            subject.model.chooseAdd(SearchTarget.OfAlbum(album))
            subject.model.add(AddPlacement.END)
            assertEquals(calls, gateway.calls.size)
            assertEquals(listOf("q", "t1", "a1", "a2", "a1", "a2"), subject.harness.engine.ids())
            subject.model.search()
            assertEquals(emptyMap<Any, Any>(), subject.model.ui.value.added)
        }

    @Test
    fun `HOST-38 in a jam a found track goes to the jam, is marked sending until the state brings it`() =
        runTest {
            val subject = Subject(this)
            gateway.found = { found }
            gateway.jam.value = jamState()
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            assertEquals(SearchJam(connected = true), subject.model.ui.value.jam)
            subject.model.chooseAdd(SearchTarget.OfTrack(track("t1")))
            subject.model.add(AddPlacement.END)
            subject.model.chooseAdd(SearchTarget.OfTrack(track("t2")))
            subject.model.add(AddPlacement.NEXT)
            runCurrent()
            assertEquals(listOf("jam add t1", "jam next t2"), gateway.calls.filter { it.startsWith("jam") })
            assertEquals(setOf("t1", "t2"), subject.model.ui.value.sending)
            assertEquals(listOf("jam_added", "jam_added_next"), subject.notices)
            assertTrue(subject.harness.controller.state.value.entries.isEmpty())
            gateway.jam.value = jamState(item("t1", "g1"))
            runCurrent()
            assertEquals(setOf("t2"), subject.model.ui.value.sending)
            assertEquals(mapOf("t1" to "Маша"), subject.model.ui.value.jam?.waiting)
        }

    @Test
    fun `HOST-38 a refused add shows on its row, a dropped connection and the jam's end clear the marks`() =
        runTest {
            val subject = Subject(this)
            gateway.found = { found }
            gateway.jam.value = jamState()
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            for (id in listOf("t1", "t2")) {
                subject.model.chooseAdd(SearchTarget.OfTrack(track(id)))
                subject.model.add(AddPlacement.END)
            }
            gateway.jamEvents.emit(JamHostEvent.AddRefused("t1", "queue-full"))
            runCurrent()
            assertEquals(setOf("t2"), subject.model.ui.value.sending)
            assertEquals(mapOf("t1" to "queue-full"), subject.model.ui.value.refused)
            gateway.jam.value = jamState().copy(connected = false)
            runCurrent()
            assertEquals(emptySet<String>(), subject.model.ui.value.sending)
            assertEquals(false, subject.model.ui.value.jam?.connected)
            gateway.jam.value = JamHostState()
            runCurrent()
            assertNull(subject.model.ui.value.jam)
            assertEquals(emptyMap<String, String>(), subject.model.ui.value.refused)
        }

    @Test
    fun `HOST-25 HOST-37 without a connection nothing is sent, a Spotify track cannot go to the jam`() =
        runTest {
            val subject = Subject(this)
            gateway.found = { found }
            gateway.jam.value = jamState()
            gateway.jamSends = false
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            subject.model.chooseAdd(SearchTarget.OfTrack(track("t1")))
            subject.model.add(AddPlacement.END)
            runCurrent()
            assertEquals(listOf("jam_no_connection"), subject.notices)
            assertEquals(emptySet<String>(), subject.model.ui.value.sending)
            assertTrue(subject.model.jamAddable(track("t1")))
            assertEquals(false, subject.model.jamAddable(spotify("s1")))
        }

    @Test
    fun `HOST-38 HOST-21 in a jam a found track played is the new base from it, an album is not added`() =
        runTest {
            val subject = Subject(this)
            gateway.found = { found }
            gateway.jam.value = jamState()
            subject.model.open("кино", SearchSource.YANDEX)
            runCurrent()
            subject.model.chooseAdd(SearchTarget.OfAlbum(album))
            subject.model.add(AddPlacement.END)
            runCurrent()
            assertEquals(listOf("find YANDEX кино"), gateway.calls)
            subject.model.play(track("t1"))
            runCurrent()
            assertEquals(listOf("best", "t1", "t2"), subject.harness.engine.ids())
        }

    private fun jamState(vararg queue: JamQueueItem) = JamHostState(
        phase = JamHostPhase.ACTIVE,
        connected = true,
        room = ROOM.copy(
            participants = listOf(
                JamParticipant("g1", "Маша", ParticipantKind.WEB, online = true, pending = 0),
            ),
            queue = queue.toList(),
        ),
    )

    private fun item(trackId: String, addedBy: String) = JamQueueItem(
        itemId = "i-$trackId",
        track = JamTrack(id = trackId, title = "T$trackId", artists = listOf("A"), durationMs = 1_000),
        addedBy = addedBy,
        addedAt = 0,
        pinned = false,
    )

    private inner class Subject(scope: TestScope) {
        val harness = QueueHarness(scope)
        val model =
            SearchViewModel(
                gateway = gateway,
                queue = harness.controller,
                strings = ::text,
                io = StandardTestDispatcher(scope.testScheduler),
            )

        val notices = ArrayList<String>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                model.notices.collect { notices += it }
            }
        }

        fun currentId(): String = harness.engine.tracks[harness.engine.currentIndex].id
    }

    private class FakeGateway : SearchGateway {
        val calls = ArrayList<String>()
        var allowed = SearchSource.entries.toSet()
        var found: suspend (String) -> Found = { error("unexpected find $it") }
        var tracks: suspend (FoundPick) -> List<Track> = { error("unexpected tracks $it") }

        override fun canSearch(source: SearchSource): Boolean = source in allowed

        override suspend fun find(source: SearchSource, text: String): Found {
            calls += "find $source $text"
            return found(text)
        }

        override suspend fun tracksOf(source: SearchSource, pick: FoundPick): List<Track> {
            calls += "tracks $source ${pick.kind} ${pick.id}"
            return tracks(pick)
        }

        override fun autoplay(contextUri: String): Continuation {
            calls += "autoplay $contextUri"
            return FakeContinuation(ContinuationKey("spotify-autoplay", contextUri)).apply { replies() }
        }

        override val jam = MutableStateFlow(JamHostState())
        override val jamEvents = MutableSharedFlow<JamHostEvent>(extraBufferCapacity = 8)
        var jamSends = true

        override fun jamAdd(track: Track): Boolean {
            calls += "jam add ${track.id}"
            return jamSends
        }

        override fun jamPlayNext(track: Track): Boolean {
            calls += "jam next ${track.id}"
            return jamSends
        }

        override fun jamCanAdd(track: Track): Boolean = track.source == TrackSource.YANDEX
    }

    private companion object {
        val ROOM: JamRoom by lazy {
            val text = File(Spec.root, "jam/protocol/examples/state/host.json").readText()
            ((JamCodec.decodeServer(text) as Decoded.Message).message as State).room
        }
        val NAMES: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val artist = FoundPick(FoundPick.Kind.ARTIST, "9", "Кино")
        val album = FoundPick(FoundPick.Kind.ALBUM, "4", "Группа крови", listOf("Кино"), 1988)
        val found =
            Found(
                bestTrack = track("best"),
                tracks = listOf(track("t1"), track("t2")),
                artists = listOf(artist),
                albums = listOf(album),
            )

        fun track(id: String, available: Boolean = true) = Track(id, "T$id", available = available)

        fun spotify(id: String) = Track(id, "S$id", source = TrackSource.SPOTIFY)

        fun text(id: Int, args: List<Any>): String {
            val name = NAMES.getValue(id)
            return if (args.isEmpty()) name else "$name(${args.joinToString()})"
        }
    }
}
