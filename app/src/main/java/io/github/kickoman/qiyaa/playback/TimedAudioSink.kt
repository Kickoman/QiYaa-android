package io.github.kickoman.qiyaa.playback

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.kickoman.qiyaa.audio.VisualizerTap
import java.nio.ByteBuffer

@UnstableApi
class TimedAudioSink(
    sink: AudioSink,
    private val tap: VisualizerTap,
    private val clock: () -> Long = System::nanoTime,
) : ForwardingAudioSink(sink) {
    private var lastLogNanos = 0L

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        tap.announceInput(presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val positionUs = super.getCurrentPositionUs(sourceEnded)
        if (positionUs != AudioSink.CURRENT_POSITION_NOT_SET) {
            val now = clock()
            tap.reportPlaying(positionUs, now)
            logLag(now)
        }
        return positionUs
    }

    private fun logLag(now: Long) {
        if (now - lastLogNanos < LOG_EVERY_NANOS) return
        lastLogNanos = now
        val lagUs = tap.lagUs(now) ?: return
        Log.d(LOG_TAG, "Visualizer: the newest written audio sounds in ${lagUs / MICROS_PER_MILLI} ms")
    }

    private companion object {
        const val LOG_EVERY_NANOS = 5_000_000_000L
        const val MICROS_PER_MILLI = 1_000L
    }
}
