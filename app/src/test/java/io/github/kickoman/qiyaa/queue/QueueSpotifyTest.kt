package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSpotifyTest {
    @Test
    fun `SPOT-19 CONT-05 a Spotify track sends no play-audio and no feedback, and never becomes a seed`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            h.controller.setQueue(
                listOf(spotify(SPOTIFY_ID), yandex("1")),
                "Mix",
                false,
                autoplay = true,
                sourceId = null,
            )
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.startPlaying()
            runCurrent()
            assertEquals(listOf("1" to "play-1"), h.source.reports)
            assertEquals(emptyList<String>(), h.source.feedback)
            assertEquals(listOf("1"), h.store.snapshot()?.history)
            assertTrue(h.source.calls.none { SPOTIFY_ID in it })
        }

    @Test
    fun `SPOT-19 no rotor feedback for a Spotify track, even from a row with a wave context`() = runTest {
        val wave =
            QueueSnapshot(
                tracks = listOf(spotify(SPOTIFY_ID), yandex("2")),
                batchIds = listOf("B1", "B1"),
                title = "My Wave",
                sourceId = "user:onyourwave",
                isWave = true,
                waveSessionId = "S1",
                waveStationId = "user:onyourwave",
                index = 0,
                positionMs = 0,
                shuffle = false,
                repeat = false,
            )
        val h = QueueHarness(this, store = FakeQueueStore(QueueSnapshotCodec.encode(wave)))
        h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        h.engine.startPlaying()
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("trackStarted S1/user:onyourwave/B1 2 0.0"), h.source.feedback)
        assertEquals(listOf("2" to "play-1"), h.source.reports)
        assertTrue(h.source.calls.toString(), h.source.calls.none { SPOTIFY_ID in it })
    }

    @Test
    fun `SPOT-19 a Spotify track is never liked or disliked, a dislike of Yandex's 42 skips no Spotify 42`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Spotify") { listOf(spotify("42"), spotify("43")) }
            runCurrent()
            h.controller.setLiked(spotify("42"), true)
            h.controller.toggleLike(spotify("42"))
            h.controller.likeCurrent()
            h.controller.dislikeCurrent()
            h.controller.dislike(spotify("42"))
            h.controller.dislike(yandex("42"))
            runCurrent()
            assertEquals(
                listOf("dislike 42"),
                h.source.calls.filter {
                    it.startsWith("setLiked") ||
                        it.startsWith("dislike")
                },
            )
            assertEquals(0, h.engine.currentIndex)
        }

    @Test
    fun `CONT-04 WAVE-05 the wave from history asks for more with the Yandex tracks of the queue only`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            val more = CompletableDeferred<WaveBatch>()
            h.source.onStartWave = { WaveBatch("V1", "B1", QueueHarness.tracks("v1", "v2")) }
            h.source.onMoreWave = { _, _ -> more.await() }
            h.controller.loadSource("Mix") { listOf(yandex("a"), spotify(SPOTIFY_ID), yandex("b")) }
            runCurrent()
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            repeat(3) { h.engine.finishTrack() }
            runCurrent()
            assertEquals(listOf("startWave [track:a]", "moreWave V1 [a, b, v1, v2]"), h.source.calls)
        }

    @Test
    fun `QUAL-05 a Spotify next track is never checked against Yandex's links or loaded again`() = runTest {
        val h = QueueHarness(this)
        h.controller.loadSource("Spotify") { listOf(spotify("1"), spotify("2")) }
        runCurrent()
        val asked = ArrayList<String>()
        h.controller.onFilesChanged {
            asked += it
            true
        }
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<String>(), h.engine.commands.filter { it.startsWith("reload") })
    }

    private fun yandex(id: String) = Track(id = id, title = "T$id")

    private fun spotify(id: String) = Track(id = id, title = "Spotify $id", source = TrackSource.SPOTIFY)

    private companion object {
        const val SPOTIFY_ID = "6PCoZdjSPbRMehHviTrpB3"
    }
}
