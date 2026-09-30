package io.github.kickoman.qiyaa.jam

import io.github.kickoman.qiyaa.support.FakeJamTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JamClientTest {
    @Test
    fun `says hello on open and goes online with the clock offset after welcome`() = runTest {
        val jam = harness()
        jam.client.start("wss://jam.example.org/ws")
        runCurrent()
        assertEquals("wss://jam.example.org/ws", jam.transport.last.url)
        jam.transport.last.open()
        runCurrent()
        assertEquals(
            Decoded.Message(Hello(1, JamApp.ANDROID, "test")),
            JamCodec.decodeClient(jam.transport.last.sent[0]),
        )
        jam.transport.last.receive("""{"type":"welcome","protocol":1,"serverTime":${currentTime + 1_500}}""")
        runCurrent()
        assertEquals(JamStatus.ONLINE, jam.client.status.value)
        assertEquals(1, jam.handler.welcomes)
        assertEquals(1_500L, jam.client.clockOffsetMs)
    }

    @Test
    fun `reconnects after 1, 2, 4, 8, 16 and then every 30 s, and starts over after a welcome`() = runTest {
        val jam = harness()
        jam.client.start("wss://jam.example.org/ws")
        runCurrent()
        for (delayMs in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            val sockets = jam.transport.sockets.size
            jam.transport.last.drop()
            runCurrent()
            assertEquals(JamStatus.OFFLINE, jam.client.status.value)
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals("still waiting at ${delayMs - 1} ms", sockets, jam.transport.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("reconnected after $delayMs ms", sockets + 1, jam.transport.sockets.size)
        }
        jam.online()
        jam.transport.last.drop()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(JamStatus.CONNECTING, jam.client.status.value)
    }

    @Test
    fun `reconnects at once when the network comes back`() = runTest {
        val jam = harness()
        jam.client.start("wss://jam.example.org/ws")
        jam.online()
        jam.connectivity.value = false
        jam.transport.last.drop()
        runCurrent()
        val sockets = jam.transport.sockets.size
        jam.connectivity.value = true
        runCurrent()
        assertEquals(sockets + 1, jam.transport.sockets.size)
    }

    @Test
    fun `started waits in the outbox without a connection and goes out when online`() = runTest {
        val jam = harness()
        jam.client.start("wss://jam.example.org/ws")
        runCurrent()
        jam.client.started("i4")
        assertEquals(listOf("i4"), jam.client.outbox.value)
        jam.online()
        jam.client.started("i5")
        assertEquals(listOf("i4"), jam.client.outbox.value)
        assertEquals(Decoded.Message(Started("i5")), JamCodec.decodeClient(jam.transport.last.sent.last()))
        jam.client.clearOutbox()
        assertEquals(emptyList<String>(), jam.client.outbox.value)
    }

    @Test
    fun `ended, kicked and update-required stop the reconnecting`() = runTest {
        for (message in listOf(
            """{"type":"ended","reason":"host-ended"}""",
            """{"type":"kicked"}""",
            """{"type":"rejected","reason":"update-required","serverProtocol":2}""",
        )) {
            val jam = harness()
            jam.client.start("wss://jam.example.org/ws")
            jam.online()
            jam.transport.last.receive(message)
            runCurrent()
            assertEquals(message, JamStatus.STOPPED, jam.client.status.value)
            val sockets = jam.transport.sockets.size
            jam.transport.last.drop()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(message, sockets, jam.transport.sockets.size)
            assertEquals(1, jam.handler.messages.size)
        }
    }

    @Test
    fun `an invalid message is dropped, an unknown reason still arrives, send fails while offline`() =
        runTest {
            val jam = harness()
            jam.client.start("wss://jam.example.org/ws")
            runCurrent()
            assertFalse(jam.client.send(RotateLink("h1")))
            jam.online()
            jam.transport.last.receive("""{"type":"ack"}""")
            jam.transport.last.receive("""{"type":"rejected","id":"h1","reason":"too-loud"}""")
            runCurrent()
            assertEquals(
                listOf<ServerMessage>(Rejected(id = "h1", reason = "too-loud")),
                jam.handler.messages,
            )
            assertTrue(jam.client.send(RotateLink("h1")))
        }

    @Test
    fun `socketUrl turns the server address into its WebSocket`() {
        assertEquals("wss://jam.example.org/ws", JamClient.socketUrl("https://jam.example.org/"))
        assertEquals("ws://192.168.0.5:8090/ws", JamClient.socketUrl(" http://192.168.0.5:8090 "))
        assertEquals("wss://example.org:8443/ws", JamClient.socketUrl("example.org:8443"))
    }

    private class RecordingHandler : JamHandler {
        var welcomes = 0
        val messages = mutableListOf<ServerMessage>()

        override fun onWelcome() {
            welcomes++
        }

        override fun onMessage(message: ServerMessage) {
            messages += message
        }
    }

    private class Harness(
        val scope: TestScope,
        val client: JamClient,
        val transport: FakeJamTransport,
        val handler: RecordingHandler,
        val connectivity: MutableStateFlow<Boolean>,
    ) {
        fun online() {
            scope.runCurrent()
            transport.last.open()
            transport.last.receive("""{"type":"welcome","protocol":1,"serverTime":0}""")
            scope.runCurrent()
        }
    }

    private fun TestScope.harness(): Harness {
        val transport = FakeJamTransport()
        val handler = RecordingHandler()
        val connectivity = MutableStateFlow(true)
        val client =
            JamClient(
                transport = transport,
                connectivity = connectivity,
                scope = backgroundScope,
                appVersion = "test",
                handler = handler,
                clock = { testScheduler.currentTime },
            )
        return Harness(this, client, transport, handler, connectivity)
    }
}
