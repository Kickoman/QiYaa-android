package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.Ack
import io.github.kickoman.qiyaa.jam.Add
import io.github.kickoman.qiyaa.jam.ClientMessage
import io.github.kickoman.qiyaa.jam.Command
import io.github.kickoman.qiyaa.jam.CommandKind
import io.github.kickoman.qiyaa.jam.Create
import io.github.kickoman.qiyaa.jam.Created
import io.github.kickoman.qiyaa.jam.Decoded
import io.github.kickoman.qiyaa.jam.End
import io.github.kickoman.qiyaa.jam.EndReason
import io.github.kickoman.qiyaa.jam.Ended
import io.github.kickoman.qiyaa.jam.Hello
import io.github.kickoman.qiyaa.jam.JamCodec
import io.github.kickoman.qiyaa.jam.JamFallback
import io.github.kickoman.qiyaa.jam.JamQueueItem
import io.github.kickoman.qiyaa.jam.JamRoom
import io.github.kickoman.qiyaa.jam.JamSession
import io.github.kickoman.qiyaa.jam.JamSessionCodec
import io.github.kickoman.qiyaa.jam.JamSessionStore
import io.github.kickoman.qiyaa.jam.JamStatus
import io.github.kickoman.qiyaa.jam.JamStore
import io.github.kickoman.qiyaa.jam.JamTrack
import io.github.kickoman.qiyaa.jam.LinkRotated
import io.github.kickoman.qiyaa.jam.NowPlayingSource
import io.github.kickoman.qiyaa.jam.OutboxEntry
import io.github.kickoman.qiyaa.jam.Pin
import io.github.kickoman.qiyaa.jam.Playing
import io.github.kickoman.qiyaa.jam.Rejected
import io.github.kickoman.qiyaa.jam.Resume
import io.github.kickoman.qiyaa.jam.Resumed
import io.github.kickoman.qiyaa.jam.SearchError
import io.github.kickoman.qiyaa.jam.SearchRequest
import io.github.kickoman.qiyaa.jam.SearchResult
import io.github.kickoman.qiyaa.jam.ServerMessage
import io.github.kickoman.qiyaa.jam.Snapshot
import io.github.kickoman.qiyaa.jam.Started
import io.github.kickoman.qiyaa.jam.State
import io.github.kickoman.qiyaa.jam.ValidateEntry
import io.github.kickoman.qiyaa.jam.ValidateReason
import io.github.kickoman.qiyaa.jam.ValidateRequest
import io.github.kickoman.qiyaa.jam.ValidateResult
import io.github.kickoman.qiyaa.jam.Welcome
import io.github.kickoman.qiyaa.support.FakeJamTransport
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.Spec
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.Track
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JamHostTest {
    @Test
    fun `create goes out after welcome, and created stores the session and starts the jam mode`() = runTest {
        val h = Harness(this)
        assertTrue(h.host.create("  Маша  "))
        assertEquals(JamHostPhase.CREATING, h.host.state.value.phase)
        h.open()
        assertEquals("wss://jam.example.org/ws", h.transport.last.url)
        assertEquals(listOf(Create("r1", HOST_KEY, "Маша")), h.sent())
        h.receive(Created("r1", ROOM, HOST_SECRET, JOIN_SECRET, JOIN_URL, HOST_ID))
        assertEquals(JamSession(ROOM, HOST_SECRET, JOIN_URL, null, emptyList()), h.stored())
        val state = h.host.state.value
        assertEquals(JamHostPhase.ACTIVE, state.phase)
        assertTrue(state.connected)
        assertEquals(JOIN_URL, state.joinUrl)
        assertTrue(h.queue.controller.isJamActive)
        assertEquals(Playing(NowPlayingSource.IDLE, positionMs = 0, paused = true), h.sent().last())
    }

    @Test
    fun `create refuses a missing server, a malformed host key and a blank name`() = runTest {
        val h = Harness(this)
        assertFalse(h.host.create("   "))
        h.config = h.config.copy(hostKey = "qjk_short")
        assertFalse(h.host.create("Маша"))
        h.config = h.config.copy(serverUrl = "", hostKey = HOST_KEY)
        assertFalse(h.host.create("Маша"))
        runCurrent()
        assertTrue(h.transport.sockets.isEmpty())
        assertEquals(JamHostPhase.NONE, h.host.state.value.phase)
    }

    @Test
    fun `a refused create reports the reason and leaves no jam`() = runTest {
        val h = Harness(this)
        h.host.create("Маша")
        h.open()
        h.receive(Rejected("r1", "bad-key"))
        assertEquals(listOf<JamHostEvent>(JamHostEvent.Refused("bad-key")), h.events)
        assertEquals(JamHostPhase.NONE, h.host.state.value.phase)
        assertEquals(JamStatus.STOPPED, h.host.state.value.connection)
        assertFalse(h.queue.controller.isJamActive)
    }

    @Test
    fun `HOST-01 states feed the queue, an older version is ignored, and a reconnect takes the first one`() =
        runTest {
            val h = Harness(this)
            h.created()
            h.state(5, item("i1", "11"))
            assertEquals(listOf("11"), h.queue.engine.ids())
            h.state(4, item("i1", "11"), item("i2", "22"))
            assertEquals(listOf("11"), h.queue.engine.ids())
            h.state(6, item("i1", "11"), item("i2", "22"))
            assertEquals(listOf("11", "22"), h.queue.engine.ids())
            h.transport.last.drop()
            advanceTimeBy(1_000)
            h.open()
            h.receive(Resumed("r2", restored = true))
            h.state(2, item("i2", "22"), item("i3", "33"))
            assertEquals(listOf("11", "22", "33"), h.queue.engine.ids())
            assertEquals(2, h.host.state.value.room?.queue?.size)
        }

    @Test
    fun `HOST-05 HOST-25 HOST-26 started goes out, waits in the stored outbox offline, goes with resume`() =
        runTest {
            val h = Harness(this)
            h.created()
            h.state(1, item("i1", "11"), item("i2", "22"))
            assertTrue(Started("i1") in h.sent())
            h.transport.last.drop()
            runCurrent()
            assertFalse(h.host.state.value.connected)
            val offline = h.transport.last.sent.size
            h.queue.engine.finishTrack()
            runCurrent()
            assertEquals(offline, h.transport.last.sent.size)
            assertEquals(listOf("i2"), h.stored()?.outbox)
            assertFalse(h.host.pin("i2"))
            advanceTimeBy(1_000)
            h.open()
            assertEquals(
                listOf(
                    Resume("r2", ROOM, HOST_SECRET, HOST_KEY, JsonNull, listOf(OutboxEntry(itemId = "i2"))),
                ),
                h.sent(),
            )
            h.receive(Resumed("r2", restored = false))
            assertEquals(emptyList<String>(), h.stored()?.outbox)
            assertEquals(
                Playing(NowPlayingSource.ITEM, itemId = "i2", positionMs = 0, paused = true),
                h.sent().last(),
            )
            assertTrue(h.host.pin("i2"))
        }

    @Test
    fun `HOST-22 the latest snapshot and a new link are stored`() = runTest {
        val h = Harness(this)
        h.created()
        val snapshot = snapshotData()
        h.receive(Snapshot(snapshot))
        assertEquals(snapshot, h.stored()?.snapshot)
        val url = "https://jam.example.org/j/$ROOM#AAAAAAAAAAAAAAAAAAAAAA"
        h.receive(LinkRotated("r9", "AAAAAAAAAAAAAAAAAAAAAA", url))
        assertEquals(url, h.stored()?.joinUrl)
        assertEquals(url, h.host.state.value.joinUrl)
    }

    @Test
    fun `HOST-23 continue resumes with the stored snapshot and outbox, and the jam mode starts at once`() =
        runTest {
            val snapshot = snapshotData()
            val h = Harness(this, JamSession(ROOM, HOST_SECRET, JOIN_URL, snapshot, listOf("i7")))
            assertTrue(h.host.state.value.storedSession)
            h.host.continueStored()
            assertTrue(h.queue.controller.isJamActive)
            assertFalse(h.host.state.value.storedSession)
            h.open()
            assertEquals(
                listOf(
                    Resume("r1", ROOM, HOST_SECRET, HOST_KEY, snapshot, listOf(OutboxEntry(itemId = "i7"))),
                ),
                h.sent(),
            )
            h.receive(Resumed("r1", restored = true))
            assertTrue(h.host.state.value.connected)
            assertEquals(emptyList<String>(), h.stored()?.outbox)
        }

    @Test
    fun `HOST-23 no clears the storage at once, then resumes the room only to end it`() = runTest {
        val h = Harness(this, JamSession(ROOM, HOST_SECRET, JOIN_URL, null, emptyList()))
        h.host.discardStored()
        runCurrent()
        assertNull(h.store.text)
        assertFalse(h.queue.controller.isJamActive)
        h.open()
        assertEquals(listOf(Resume("r1", ROOM, HOST_SECRET, HOST_KEY, JsonNull, emptyList())), h.sent())
        h.receive(Resumed("r1", restored = false))
        assertEquals(End("r2"), h.sent().last())
        h.receive(Ack("r2"))
        assertEquals(1000, h.transport.last.closedWith)
        assertEquals(JamHostPhase.NONE, h.host.state.value.phase)
    }

    @Test
    fun `HOST-24 a resume refused for a room that is gone ends the jam`() = runTest {
        val h = Harness(this, JamSession(ROOM, HOST_SECRET, JOIN_URL, null, emptyList()))
        h.host.continueStored()
        h.open()
        h.receive(Rejected("r1", "room-not-found"))
        assertEquals(listOf<JamHostEvent>(JamHostEvent.Ended(JamHostEvent.Ended.Why.GONE)), h.events)
        assertNull(h.store.text)
        assertFalse(h.queue.controller.isJamActive)
        assertNull(h.queue.controller.state.value.jamSlots)
        assertEquals(JamHostPhase.NONE, h.host.state.value.phase)
    }

    @Test
    fun `a resume refused for another reason is tried again later`() = runTest {
        val h = Harness(this, JamSession(ROOM, HOST_SECRET, JOIN_URL, null, emptyList()))
        h.host.continueStored()
        h.open()
        h.receive(Rejected("r1", "server-full"))
        assertEquals(listOf<JamHostEvent>(JamHostEvent.Refused("server-full")), h.events)
        assertEquals(1, h.transport.sockets.size)
        advanceTimeBy(JamHost.RESUME_RETRY_MS)
        runCurrent()
        assertEquals(2, h.transport.sockets.size)
        assertTrue(h.queue.controller.isJamActive)
    }

    @Test
    fun `HOST-28 HOST-29 a guest's search answers with up to 20 available tracks, or the error`() = runTest {
        val h = Harness(this)
        h.created()
        h.catalog.onSearch = { text ->
            assertEquals("кино", text)
            (1..30).map { Track(id = "$it", title = "T$it", artists = listOf("Кино"), available = it != 2) }
        }
        h.receive(SearchRequest("q1", "кино"))
        val reply = h.sent().last() as SearchResult
        assertEquals("q1", reply.requestId)
        assertEquals((1..21).filter { it != 2 }.map { "$it" }, reply.tracks?.map { it.id })
        h.catalog.onSearch = { throw IOException("offline") }
        h.receive(SearchRequest("q2", "кино"))
        assertEquals(SearchResult("q2", error = SearchError.FAILED), h.sent().last())
        h.catalog.onSearch = { throw HttpException(401, "GET", "/search", "Unauthorized") }
        h.receive(SearchRequest("q3", "кино"))
        assertEquals(SearchResult("q3", error = SearchError.UNAUTHORIZED), h.sent().last())
    }

    @Test
    fun `HOST-30 a check answers for every id in order, and a failure fails them all`() = runTest {
        val h = Harness(this)
        h.created()
        h.catalog.onTracks =
            { listOf(Track(id = "22", title = "B", available = false), Track(id = "11", title = "A")) }
        h.receive(ValidateRequest("v1", listOf("11", "22", "33")))
        assertEquals(
            ValidateResult(
                "v1",
                listOf(
                    ValidateEntry(
                        "11",
                        track = JamTrack(id = "11", title = "A", artists = emptyList(), durationMs = 0),
                    ),
                    ValidateEntry("22", reason = ValidateReason.TRACK_UNAVAILABLE),
                    ValidateEntry("33", reason = ValidateReason.TRACK_UNAVAILABLE),
                ),
            ),
            h.sent().last(),
        )
        h.catalog.onTracks = { throw IOException("offline") }
        h.receive(ValidateRequest("v2", listOf("11", "22")))
        assertEquals(
            ValidateResult(
                "v2",
                listOf(
                    ValidateEntry("11", reason = ValidateReason.FAILED),
                    ValidateEntry("22", reason = ValidateReason.FAILED),
                ),
            ),
            h.sent().last(),
        )
    }

    @Test
    fun `HOST-18 HOST-19 a skip command moves on only for the current item`() = runTest {
        val h = Harness(this)
        h.created()
        h.state(1, item("i1", "11"), item("i2", "22"))
        h.receive(Command(CommandKind.SKIP, "i2"))
        assertEquals(0, h.queue.engine.currentIndex)
        h.receive(Command(CommandKind.SKIP, "i1"))
        assertEquals(1, h.queue.engine.currentIndex)
    }

    @Test
    fun `HOST-32 the host ends the jam - end goes out, the storage is cleared, jam items stay`() = runTest {
        val h = Harness(this)
        h.created()
        h.state(1, item("i1", "11"), item("i2", "22"))
        h.host.end()
        runCurrent()
        assertTrue(h.sent().last() is End)
        assertEquals(1000, h.transport.last.closedWith)
        assertNull(h.store.text)
        assertFalse(h.queue.controller.isJamActive)
        assertEquals(listOf("11", "22"), h.queue.engine.ids())
        assertEquals(listOf<JamHostEvent>(JamHostEvent.Ended(JamHostEvent.Ended.Why.BY_HOST)), h.events)
        assertEquals(JamHostPhase.NONE, h.host.state.value.phase)
    }

    @Test
    fun `HOST-32 ended from the server ends the jam here without an end`() = runTest {
        val h = Harness(this)
        h.created()
        h.receive(Ended(EndReason.EXPIRED))
        assertFalse(h.sent().any { it is End })
        assertEquals(listOf<JamHostEvent>(JamHostEvent.Ended(JamHostEvent.Ended.Why.EXPIRED)), h.events)
        assertNull(h.store.text)
        assertFalse(h.queue.controller.isJamActive)
    }

    @Test
    fun `HOST-20 the host adds and plays next, and the pin waits for the item in the state`() = runTest {
        val h = Harness(this)
        h.created()
        val track = Track(id = "44", title = "D", artists = listOf("Сплин"))
        assertTrue(h.host.add(track))
        assertEquals(Add("r2", track = JamTracks.toJam(track)), h.sent().last())
        assertTrue(h.host.playNext(Track(id = "55", title = "E")))
        assertTrue(h.sent().last() is Add)
        h.state(1, item("i1", "11"), item("i2", "44", HOST_ID), item("i3", "55", HOST_ID))
        assertEquals(Pin("r4", "i3"), h.sent().last())
        h.state(2, item("i1", "11"), item("i2", "44", HOST_ID), item("i3", "55", HOST_ID))
        assertEquals(1, h.sent().count { it is Pin })
        assertTrue(h.host.playNext(track))
        assertEquals(Pin("r5", "i2"), h.sent().last())
    }

    @Test
    fun `the connection lives with the playback service`() = runTest {
        val h = Harness(this)
        h.created()
        h.host.onServiceStopped()
        runCurrent()
        assertEquals(1000, h.transport.last.closedWith)
        assertEquals(JamHostPhase.ACTIVE, h.host.state.value.phase)
        assertFalse(h.host.state.value.connected)
        h.host.onServiceStarted()
        h.open()
        assertTrue(h.sent().single() is Resume)
    }

    private class Harness(val scope: TestScope, stored: JamSession? = null) {
        val queue = QueueHarness(scope)
        val transport = FakeJamTransport()
        val catalog = FakeCatalog()
        val store = MemoryStore(stored?.let(JamSessionCodec::encode))
        var config = JamHostConfig("https://jam.example.org", HOST_KEY, waveFeedback = true)
        val events = ArrayList<JamHostEvent>()
        private var ids = 0
        val host =
            JamHost(
                transport = transport,
                connectivity = queue.network,
                queue = queue.controller,
                catalog = catalog,
                store = JamSessionStore(store),
                config = { config },
                queueTitle = { "Jam" },
                scope = scope.backgroundScope,
                io = StandardTestDispatcher(scope.testScheduler),
                appVersion = "test",
                newId = { "r${++ids}" },
                clock = { scope.testScheduler.currentTime },
            )

        init {
            scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                host.events.collect { events.add(it) }
            }
        }

        fun sent(): List<ClientMessage> = transport.last.sent
            .map { (JamCodec.decodeClient(it) as Decoded.Message).message }
            .filterNot { it is Hello }

        fun open() {
            scope.runCurrent()
            transport.last.open()
            receive(Welcome(1, 0))
        }

        fun receive(message: ServerMessage) {
            transport.last.receive(JamCodec.json.encodeToString(ServerMessage.serializer(), message))
            scope.runCurrent()
        }

        fun created() {
            host.create("Маша")
            open()
            receive(Created("r1", ROOM, HOST_SECRET, JOIN_SECRET, JOIN_URL, HOST_ID))
        }

        fun state(version: Long, vararg items: JamQueueItem) =
            receive(State(version, 0, room(items.toList())))

        fun stored(): JamSession? {
            scope.runCurrent()
            return JamSessionCodec.decode(store.text)
        }
    }

    private class FakeCatalog : JamCatalog {
        var onSearch: suspend (String) -> List<Track> = { error("unexpected search $it") }
        var onTracks: suspend (List<String>) -> List<Track> = { error("unexpected tracks $it") }

        override suspend fun searchTracks(text: String): List<Track> = onSearch(text)

        override suspend fun tracks(ids: List<String>): List<Track> = onTracks(ids)
    }

    private class MemoryStore(var text: String?) : JamStore {
        override fun read(): String? = text

        override fun write(text: String?) {
            this.text = text
        }
    }

    companion object {
        const val ROOM = "7k3m9q2x"
        const val HOST_ID = "h7k2m9"
        const val GUEST_ID = "a4n8q1"
        const val HOST_SECRET = "R0CuY0ewFywBJU_1W65a_1GZ9ERuf21kPUAYWz9HUUU"
        const val JOIN_SECRET = "WDkyFgMr5iV3hKwManPvsg"
        const val JOIN_URL = "https://jam.example.org/j/$ROOM#$JOIN_SECRET"
        val HOST_KEY = "qjk_" + "A".repeat(43)

        private val examples = File(Spec.root, "jam/protocol/examples")

        private val hostState: State by lazy {
            val decoded = JamCodec.decodeServer(File(examples, "state/host.json").readText())
            (decoded as Decoded.Message).message as State
        }

        fun room(queue: List<JamQueueItem>): JamRoom =
            hostState.room.copy(queue = queue, fallback = JamFallback(emptyList(), 0))

        fun item(itemId: String, trackId: String, addedBy: String = GUEST_ID) = JamQueueItem(
            itemId = itemId,
            track = JamTrack(id = trackId, title = "T$trackId", artists = listOf("A"), durationMs = 1_000),
            addedBy = addedBy,
            addedAt = 0,
            pinned = false,
        )

        fun snapshotData(): JsonObject {
            val decoded = JamCodec.decodeServer(File(examples, "snapshot/ok.json").readText())
            return ((decoded as Decoded.Message).message as Snapshot).data as JsonObject
        }
    }
}
