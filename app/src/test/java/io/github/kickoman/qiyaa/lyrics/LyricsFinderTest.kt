package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsFinderTest {
    private val trackA = Track("1", "A", listOf("Band"))
    private val trackB = Track("2", "B", listOf("Band"))
    private val synced = Lyrics(LyricsKind.SYNCED, listOf(LyricsLine(1_000, "one")))
    private val plain = Lyrics(LyricsKind.PLAIN, listOf(LyricsLine(null, "words")))

    @Test
    fun `LYR-01 a closed view asks nothing, whatever the track does`() = runTest {
        val h = Harness(this, active = false)
        h.track.value = trackA
        runCurrent()
        h.track.value = trackB
        runCurrent()
        assertEquals(emptyList<String>(), h.source.asked)
        assertEquals(LyricsView(), h.view())
        h.active.value = true
        runCurrent()
        assertEquals(listOf("2"), h.source.asked)
        assertEquals(LyricsPhase.LOADING, h.view().phase)
    }

    @Test
    fun `LYR-03 the service is asked when the source has no synced text, and the better text wins`() =
        runTest {
            val h = Harness(this, service = true)
            h.track.value = trackA
            runCurrent()
            assertEquals(emptyList<String>(), h.thirdParty.asked)
            h.source.answer("1", LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
            runCurrent()
            assertEquals(listOf("1"), h.thirdParty.asked)
            assertEquals(
                LyricsView(trackA, LyricsPhase.SHOWN, plain, LyricsFrom.CATALOG, better = true),
                h.view(),
            )
            h.thirdParty.answer("1", LyricsAnswer.Text(synced, LyricsFrom.CATALOG))
            runCurrent()
            assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, synced, LyricsFrom.THIRD_PARTY), h.view())
            assertEquals(listOf(LyricsLookup(LyricsShown.THIRD_PARTY_SYNCED, false)), h.lookups)
        }

    @Test
    fun `LYR-03 a source silent for 1 s lets the service in, and its text waits for the source's`() =
        runTest {
            val h = Harness(this, service = true)
            h.track.value = trackA
            runCurrent()
            advanceTimeBy(999)
            assertEquals(emptyList<String>(), h.thirdParty.asked)
            advanceTimeBy(2)
            assertEquals(listOf("1"), h.thirdParty.asked)
            assertEquals(LyricsPhase.LOADING, h.view().phase)
            h.thirdParty.answer("1", LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
            runCurrent()
            assertEquals(
                LyricsView(trackA, LyricsPhase.SHOWN, plain, LyricsFrom.THIRD_PARTY, better = true),
                h.view(),
            )
            h.source.answer("1", LyricsAnswer.Text(synced, LyricsFrom.CATALOG))
            runCurrent()
            assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, synced, LyricsFrom.CATALOG), h.view())
        }

    @Test
    fun `LYR-03 a synced text of the source cancels a service still asked`() = runTest {
        val h = Harness(this, service = true)
        h.track.value = trackA
        runCurrent()
        advanceTimeBy(1_001)
        h.source.answer("1", LyricsAnswer.Text(synced, LyricsFrom.CATALOG))
        runCurrent()
        assertEquals(listOf("1"), h.thirdParty.cancelled)
        assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, synced, LyricsFrom.CATALOG), h.view())
    }

    @Test
    fun `LYR-03 a synced text of the source means the service is never asked`() = runTest {
        val h = Harness(this, service = true)
        h.track.value = trackA
        runCurrent()
        h.source.answer("1", LyricsAnswer.Text(synced, LyricsFrom.LRC_FILE))
        advanceTimeBy(10_000)
        assertEquals(emptyList<String>(), h.thirdParty.asked)
        assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, synced, LyricsFrom.LRC_FILE), h.view())
    }

    @Test
    fun `LYR-05 the service's instrumental shows only when the source has no text`() = runTest {
        val h = Harness(this, service = true)
        h.show(trackA, LyricsAnswer.Missing())
        h.thirdParty.answer("1", LyricsAnswer.Instrumental)
        runCurrent()
        assertEquals(LyricsPhase.INSTRUMENTAL, h.view().phase)
        h.show(trackB, LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
        h.thirdParty.answer("2", LyricsAnswer.Instrumental)
        runCurrent()
        assertEquals(LyricsView(trackB, LyricsPhase.SHOWN, plain, LyricsFrom.CATALOG), h.view())
    }

    @Test
    fun `a source or a service that does not answer within 8 s has nothing`() = runTest {
        val h = Harness(this, service = true)
        h.track.value = trackA
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(listOf("1"), h.thirdParty.asked)
        advanceTimeBy(6_998)
        assertEquals(emptyList<String>(), h.source.cancelled)
        advanceTimeBy(2)
        assertEquals(listOf("1"), h.source.cancelled)
        assertEquals(LyricsView(trackA, LyricsPhase.LOADING), h.view())
        advanceTimeBy(1_000)
        assertEquals(LyricsView(trackA, LyricsPhase.MISSING), h.view())
        assertEquals(listOf("1"), h.thirdParty.cancelled)
        h.source.answer("1", LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
        runCurrent()
        assertEquals(LyricsPhase.MISSING, h.view().phase)
    }

    @Test
    fun `a source that ignores cancellation still gives way after 8 s, and the timeout is not remembered`() =
        runTest {
            var calls = 0
            val stubborn =
                LyricsSource {
                    calls++
                    withContext(NonCancellable) { delay(20_000) }
                    LyricsAnswer.Text(plain, LyricsFrom.CATALOG)
                }
            val h = Harness(this, source = stubborn)
            h.track.value = trackA
            runCurrent()
            advanceTimeBy(7_999)
            assertEquals(LyricsPhase.LOADING, h.view().phase)
            advanceTimeBy(2)
            assertEquals(LyricsView(trackA, LyricsPhase.MISSING), h.view())
            h.track.value = trackB
            runCurrent()
            h.track.value = trackA
            runCurrent()
            assertEquals(3, calls)
        }

    @Test
    fun `a service that does not answer within 8 s is asked again next time`() = runTest {
        val h = Harness(this, service = true)
        h.show(trackA, LyricsAnswer.Missing())
        advanceTimeBy(8_001)
        assertEquals(LyricsView(trackA, LyricsPhase.MISSING), h.view())
        h.show(trackB, LyricsAnswer.Missing())
        h.track.value = trackA
        runCurrent()
        assertEquals(listOf("1", "2"), h.source.asked)
        assertEquals(listOf("1", "2", "1"), h.thirdParty.asked)
    }

    @Test
    fun `LYR-09 another track drops the answers for the earlier one and looks up the new one at once`() =
        runTest {
            val h = Harness(this, service = true)
            h.track.value = trackA
            runCurrent()
            advanceTimeBy(1_001)
            h.track.value = trackB
            runCurrent()
            assertEquals(listOf("1"), h.source.cancelled)
            assertEquals(listOf("1"), h.thirdParty.cancelled)
            assertEquals(LyricsView(trackB, LyricsPhase.LOADING), h.view())
            h.source.answer("1", LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
            runCurrent()
            assertEquals(LyricsView(trackB, LyricsPhase.LOADING), h.view())
        }

    @Test
    fun `LYR-09 the same track reported again is not looked up again`() = runTest {
        val h = Harness(this)
        h.track.value = trackA
        runCurrent()
        h.track.value = trackA.copy(available = false)
        runCurrent()
        assertEquals(listOf("1"), h.source.asked)
    }

    @Test
    fun `LYR-09 a local file and a Yandex track with the same id are two tracks`() = runTest {
        val h = Harness(this)
        h.show(trackA, LyricsAnswer.Missing())
        h.track.value = trackA.copy(source = TrackSource.LOCAL)
        runCurrent()
        assertEquals(listOf("1", "1"), h.source.asked)
    }

    @Test
    fun `LYR-14 the last 10 tracks come from memory, failures and missing logins do not`() = runTest {
        val h = Harness(this)
        h.show(trackA, LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
        h.show(trackB, LyricsAnswer.Failed)
        h.track.value = trackA
        runCurrent()
        assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, plain, LyricsFrom.CATALOG), h.view())
        h.show(trackB, LyricsAnswer.NoAccount)
        h.track.value = trackA
        runCurrent()
        h.track.value = trackB
        runCurrent()
        assertEquals(listOf("1", "2", "2", "2"), h.source.asked)
        h.source.answer("2", LyricsAnswer.Missing())
        runCurrent()
        for (i in 3..10) h.show(Track("$i", "T$i"), LyricsAnswer.Missing())
        h.track.value = trackA
        runCurrent()
        assertEquals("10", h.source.asked.last())
        h.show(Track("11", "T11"), LyricsAnswer.Missing())
        h.track.value = trackA
        runCurrent()
        assertEquals("1", h.source.asked.last())
    }

    @Test
    fun `LYR-14 turning the service on asks only the service when the source's answer is remembered`() =
        runTest {
            val h = Harness(this)
            h.show(trackA, LyricsAnswer.Missing())
            h.show(trackB, LyricsAnswer.Missing())
            h.track.value = trackA
            runCurrent()
            h.service.value = true
            runCurrent()
            assertEquals(listOf("1", "2"), h.source.asked)
            assertEquals(listOf("1"), h.thirdParty.asked)
        }

    @Test
    fun `LYR-14 logging in or out forgets everything and looks up the current track again`() = runTest {
        val h = Harness(this)
        h.show(trackA, LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
        h.signedIn.value = false
        runCurrent()
        assertEquals(listOf("1", "1"), h.source.asked)
        h.source.answer("1", LyricsAnswer.NoAccount)
        h.show(trackB, LyricsAnswer.Missing())
        h.track.value = trackA
        runCurrent()
        assertEquals(listOf("1", "1", "2", "1"), h.source.asked)
    }

    @Test
    fun `LYR-15 the service turned on asks at once, turned off gives way and cancels`() = runTest {
        val h = Harness(this)
        h.show(trackA, LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
        h.service.value = true
        runCurrent()
        assertEquals(listOf("1"), h.thirdParty.asked)
        h.thirdParty.answer("1", LyricsAnswer.Text(synced, LyricsFrom.CATALOG))
        runCurrent()
        assertEquals(LyricsFrom.THIRD_PARTY, h.view().from)
        h.service.value = false
        runCurrent()
        assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, plain, LyricsFrom.CATALOG), h.view())
        h.show(trackB, LyricsAnswer.Missing())
        h.service.value = true
        runCurrent()
        h.service.value = false
        runCurrent()
        assertEquals(listOf("2"), h.thirdParty.cancelled)
        assertEquals(LyricsView(trackB, LyricsPhase.MISSING), h.view())
    }

    @Test
    fun `LYR-16 without a login the view says the lyrics need one, unless the service has a text`() =
        runTest {
            val h = Harness(this)
            h.show(trackA, LyricsAnswer.NoAccount)
            assertEquals(LyricsView(trackA, LyricsPhase.NO_ACCOUNT), h.view())
            h.service.value = true
            runCurrent()
            h.thirdParty.answer("1", LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
            runCurrent()
            assertEquals(LyricsView(trackA, LyricsPhase.SHOWN, plain, LyricsFrom.THIRD_PARTY), h.view())
        }

    @Test
    fun `LYR-17 a source that failed is asked again when the network comes back`() = runTest {
        val h = Harness(this)
        h.show(trackA, LyricsAnswer.Failed)
        assertEquals(LyricsView(trackA, LyricsPhase.FAILED), h.view())
        h.network.value = false
        runCurrent()
        h.network.value = true
        runCurrent()
        assertEquals(listOf("1", "1"), h.source.asked)
        h.source.answer("1", LyricsAnswer.Text(plain, LyricsFrom.CATALOG))
        runCurrent()
        assertEquals(LyricsPhase.SHOWN, h.view().phase)
    }

    @Test
    fun `LYR-17 a service that failed is asked again when the network comes back`() = runTest {
        val h = Harness(this, service = true)
        h.show(trackA, LyricsAnswer.Missing())
        h.thirdParty.answer("1", LyricsAnswer.Failed)
        runCurrent()
        h.network.value = false
        runCurrent()
        h.network.value = true
        runCurrent()
        assertEquals(listOf("1"), h.source.asked)
        assertEquals(listOf("1", "1"), h.thirdParty.asked)
    }

    @Test
    fun `a lookup is counted once when everyone asked answered, with the service's failure`() = runTest {
        val h = Harness(this, service = true)
        h.show(trackA, LyricsAnswer.Missing())
        h.thirdParty.answer("1", LyricsAnswer.Missing(serverError = true))
        runCurrent()
        h.show(trackB, LyricsAnswer.Text(synced, LyricsFrom.CATALOG))
        h.track.value = trackA
        runCurrent()
        h.active.value = false
        runCurrent()
        assertEquals(
            listOf(
                LyricsLookup(LyricsShown.NONE, thirdPartyFailed = true),
                LyricsLookup(LyricsShown.SOURCE_SYNCED, thirdPartyFailed = false),
            ),
            h.lookups,
        )
    }

    @Test
    fun `a remembered server error of the service is not counted again`() = runTest {
        val h = Harness(this, service = true)
        h.show(trackA, LyricsAnswer.Failed)
        h.thirdParty.answer("1", LyricsAnswer.Missing(serverError = true))
        runCurrent()
        h.show(trackB, LyricsAnswer.Text(synced, LyricsFrom.CATALOG))
        h.show(trackA, LyricsAnswer.Missing())
        assertEquals(listOf("1"), h.thirdParty.asked)
        assertEquals(
            listOf(
                LyricsLookup(LyricsShown.NONE, thirdPartyFailed = true),
                LyricsLookup(LyricsShown.SOURCE_SYNCED, thirdPartyFailed = false),
                LyricsLookup(LyricsShown.NONE, thirdPartyFailed = false),
            ),
            h.lookups,
        )
    }

    @Test
    fun `an instrumental track and a track left before its answers are each counted once`() = runTest {
        val h = Harness(this, service = true)
        h.show(trackA, LyricsAnswer.Missing())
        h.thirdParty.answer("1", LyricsAnswer.Instrumental)
        runCurrent()
        h.track.value = trackB
        runCurrent()
        h.track.value = trackA
        runCurrent()
        assertEquals(
            listOf(LyricsLookup(LyricsShown.INSTRUMENTAL, false), LyricsLookup(LyricsShown.NONE, false)),
            h.lookups,
        )
    }

    private class FakeSource : LyricsSource {
        val asked = ArrayList<String>()
        val cancelled = ArrayList<String>()
        private val pending = ArrayList<Pair<String, CompletableDeferred<LyricsAnswer>>>()

        override suspend fun lyrics(track: Track): LyricsAnswer {
            asked += track.id
            val reply = CompletableDeferred<LyricsAnswer>()
            pending += track.id to reply
            try {
                return reply.await()
            } catch (failed: CancellationException) {
                cancelled += track.id
                throw failed
            }
        }

        fun answer(id: String, answer: LyricsAnswer) {
            pending.lastOrNull { it.first == id && it.second.isActive }?.second?.complete(answer)
        }
    }

    private class Harness(
        private val scope: TestScope,
        active: Boolean = true,
        service: Boolean = false,
        source: LyricsSource? = null,
    ) {
        val track = MutableStateFlow<Track?>(null)
        val active = MutableStateFlow(active)
        val service = MutableStateFlow(service)
        val network = MutableStateFlow(true)
        val signedIn = MutableStateFlow(true)
        val source = FakeSource()
        val thirdParty = FakeSource()
        val lookups = ArrayList<LyricsLookup>()
        val finder =
            LyricsFinder(
                scope.backgroundScope,
                source ?: this.source,
                thirdParty,
                track,
                this.active,
                this.service,
                network,
                signedIn,
            )

        init {
            scope.backgroundScope.launch { finder.lookups.collect { lookups += it } }
            scope.runCurrent()
        }

        fun view(): LyricsView = finder.view.value

        fun show(next: Track, answer: LyricsAnswer) {
            track.value = next
            scope.runCurrent()
            source.answer(next.id, answer)
            scope.runCurrent()
        }
    }
}
