package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.wave
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueQualityTest {
    private val asked = ArrayList<String>()

    @Test
    fun `QUAL-05 a quality change reloads the next track and sends nothing else to the engine`() = runTest {
        val h = playing(this, "a", "b", "c")
        val before = h.controller.state.value
        val writes = h.store.writes
        h.controller.onFilesChanged(::outdated)
        runCurrent()
        assertEquals(listOf("reload 1"), h.engine.commands)
        assertEquals(before, h.controller.state.value)
        assertEquals(listOf("a", "b", "c"), h.engine.ids())
        assertEquals(listOf(0, 1, 2), h.engine.playOrder())
        assertEquals(0, h.engine.currentIndex)
        assertEquals(listOf("a" to "play-1"), h.source.reports)
        assertEquals(writes, h.store.writes)
    }

    @Test
    fun `QUAL-05 only the next track is asked about`() = runTest {
        val h = playing(this, "a", "b", "c")
        h.controller.onFilesChanged(::outdated)
        assertEquals(listOf("b"), asked)
    }

    @Test
    fun `QUAL-05 a next track asked in the quality in effect, or never asked, is not reloaded`() = runTest {
        val h = playing(this, "a", "b", "c")
        h.controller.onFilesChanged(::inEffect)
        assertEquals(listOf("b"), asked)
        assertEquals(emptyList<String>(), h.engine.commands)
    }

    @Test
    fun `QUAL-05 TR-03 with shuffle the next track of the pass is reloaded, not the next row`() = runTest {
        val h = playing(this, "a", "b", "c", "d")
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 2)
        h.engine.commands.clear()
        h.controller.onFilesChanged(::outdated)
        assertEquals(listOf("d"), asked)
        assertEquals(listOf("reload 3"), h.engine.commands)
        assertEquals(listOf(0, 3, 1, 2), h.engine.playOrder())
    }

    @Test
    fun `QUAL-05 TR-05 at the last track under repeat the first track in play order is reloaded`() = runTest {
        val h = playing(this, "a", "b", "c")
        h.controller.setShuffle(true)
        h.controller.setRepeat(true)
        h.engine.shuffleOrder = listOf(1, 0, 2)
        h.engine.seekTo(2)
        h.engine.commands.clear()
        h.controller.onFilesChanged(::outdated)
        assertEquals(listOf("b"), asked)
        assertEquals(listOf("reload 1"), h.engine.commands)
    }

    @Test
    fun `QUAL-05 TR-04 with nothing after the current track nothing is reloaded`() = runTest {
        val h = playing(this, "a", "b", "c")
        h.engine.seekTo(2)
        h.engine.commands.clear()
        h.controller.onFilesChanged(::outdated)
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<String>(), h.engine.commands)
    }

    @Test
    fun `QUAL-05 QUAL-08 a one-track queue under repeat reloads nothing`() = runTest {
        val h = playing(this, "a")
        h.controller.setRepeat(true)
        h.engine.commands.clear()
        h.controller.onFilesChanged(::outdated)
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<String>(), h.engine.commands)
    }

    @Test
    fun `QUAL-05 QUAL-08 a next row of the same track as the current one is not reloaded`() = runTest {
        val h = playing(this, "a", "a", "b")
        h.controller.onFilesChanged(::outdated)
        assertEquals(emptyList<String>(), asked)
        assertEquals(emptyList<String>(), h.engine.commands)
    }

    @Test
    fun `QUAL-05 CONT-10 while the head wraps by a pause at the end, ExoPlayer's next is still reloaded`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            h.controller.setQueue(tracks("a", "b", "c"), "Likes", isWave = false, autoplay = true, null)
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            h.controller.setRepeat(true)
            h.engine.finishTrack()
            assertEquals(listOf("a", "b", "c", "v1", "v2"), h.engine.ids())
            assertTrue(h.engine.pauseAtEndOfCurrent)
            h.engine.commands.clear()
            h.controller.onFilesChanged(::outdated)
            assertEquals(listOf("v1"), asked)
            assertEquals(listOf("reload 3"), h.engine.commands)
            h.controller.setRepeat(false)
            h.engine.finishTrack()
            assertEquals(3, h.engine.currentIndex)
        }

    @Test
    fun `QUAL-05 CONT-04 WAVE-05 the continuation's first track and a wave's next track are reloaded`() =
        runTest {
            val head = QueueHarness(this, continueWithVibe = true)
            head.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            head.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            head.controller.setQueue(tracks("a", "b"), "Likes", isWave = false, autoplay = true, null)
            head.engine.startPlaying()
            runCurrent()
            head.engine.finishTrack()
            assertEquals(listOf("a", "b", "v1", "v2"), head.engine.ids())
            head.engine.commands.clear()
            head.controller.onFilesChanged(::outdated)
            assertEquals(listOf("reload 2"), head.engine.commands)

            val waveHarness = wave(this, "w1", "w2", "w3")
            waveHarness.engine.startPlaying()
            waveHarness.engine.commands.clear()
            waveHarness.controller.onFilesChanged(::outdated)
            assertEquals(listOf("reload 1"), waveHarness.engine.commands)
            assertEquals(listOf("v1", "w2"), asked)
        }

    @Test
    fun `QUAL-05 HOST-12 in a jam a waiting item next is reloaded, and the marks and the report stay`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = Recorder()
            h.controller.startJam(jam, waveFeedback = false)
            h.controller.onJamQueue(listOf(JamEntry("i1", track("x"), "g1")), emptyList(), 0)
            assertEquals(listOf("a", "x", "b", "c"), h.engine.ids())
            val before = h.controller.state.value
            val reports = jam.playbacks.size
            h.engine.commands.clear()
            h.controller.onFilesChanged(::outdated)
            assertEquals(listOf("x"), asked)
            assertEquals(listOf("reload 1"), h.engine.commands)
            assertEquals(before, h.controller.state.value)
            assertEquals(reports, jam.playbacks.size)
            assertEquals(emptyList<String>(), jam.started)
        }

    @Test
    fun `QUAL-05 without an engine, or with an empty or ended queue, nothing happens`() = runTest {
        val detached = QueueHarness(this, attached = false)
        detached.controller.setQueue(tracks("a", "b"), "Likes", isWave = false, autoplay = true, null)
        detached.controller.onFilesChanged(::outdated)

        val empty = QueueHarness(this)
        empty.controller.onFilesChanged(::outdated)
        assertEquals(emptyList<String>(), empty.engine.commands)

        val ended = playing(this, "a", "b")
        ended.engine.finishTrack()
        ended.engine.finishTrack()
        assertTrue(ended.engine.isEnded)
        ended.controller.setRepeat(true)
        ended.engine.commands.clear()
        ended.controller.onFilesChanged(::outdated)
        assertEquals(emptyList<String>(), ended.engine.commands)
        assertEquals(emptyList<String>(), asked)
    }

    private fun outdated(trackId: String): Boolean {
        asked += trackId
        return true
    }

    private fun inEffect(trackId: String): Boolean {
        asked += trackId
        return false
    }

    private fun playing(scope: TestScope, vararg ids: String): QueueHarness {
        val harness = QueueHarness(scope)
        harness.controller.setQueue(tracks(*ids), "Likes", isWave = false, autoplay = true, sourceId = null)
        harness.engine.startPlaying()
        scope.runCurrent()
        harness.engine.commands.clear()
        return harness
    }

    private class Recorder : JamPlaybackListener {
        val started = mutableListOf<String>()
        val playbacks = mutableListOf<JamPlayback>()

        override fun onItemStarted(itemId: String) {
            started += itemId
        }

        override fun onPlayback(playback: JamPlayback) {
            playbacks += playback
        }

        override fun onRemoveItems(itemIds: List<String>) = Unit
    }
}
