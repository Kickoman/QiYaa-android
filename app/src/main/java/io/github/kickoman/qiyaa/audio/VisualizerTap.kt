package io.github.kickoman.qiyaa.audio

import java.util.concurrent.atomic.AtomicLong

class VisualizerTap(val capacityFrames: Int = DEFAULT_CAPACITY_FRAMES) {
    init {
        require(capacityFrames > 0 && capacityFrames and (capacityFrames - 1) == 0) {
            "capacity must be a power of two, got $capacityFrames"
        }
    }

    private class Playing(val ptsUs: Long, val atNanos: Long)

    private val left = FloatArray(capacityFrames)
    private val right = FloatArray(capacityFrames)
    private val cursor = AtomicLong(0)
    private val mask = capacityFrames - 1L

    private val markerFrame = LongArray(MARKERS)
    private val markerPtsUs = LongArray(MARKERS)
    private val markerRate = IntArray(MARKERS)

    @Volatile
    private var markerCount = 0

    private var nextInputPtsUs = NO_TIME

    @Volatile
    private var newestPtsUs = NO_TIME

    @Volatile
    private var playing: Playing? = null

    @Volatile
    var sampleRate: Int = EqualizerDsp.DEFAULT_SAMPLE_RATE

    fun announceInput(ptsUs: Long) {
        nextInputPtsUs = ptsUs
    }

    fun write(frames: FloatArray, frameCount: Int, channels: Int) {
        var position = cursor.get()
        val rate = sampleRate
        if (nextInputPtsUs != NO_TIME && frameCount > 0) {
            val slot = markerCount % MARKERS
            markerFrame[slot] = position
            markerPtsUs[slot] = nextInputPtsUs
            markerRate[slot] = rate
            markerCount++
            nextInputPtsUs += frameCount * MICROS_PER_SECOND / rate
            newestPtsUs = nextInputPtsUs
        }
        var sampleIndex = 0
        for (i in 0 until frameCount) {
            val slot = (position and mask).toInt()
            val leftSample = frames[sampleIndex]
            left[slot] = leftSample
            right[slot] = if (channels >= 2) frames[sampleIndex + 1] else leftSample
            position++
            sampleIndex += channels
        }
        cursor.set(position)
    }

    fun reportPlaying(ptsUs: Long, atNanos: Long) {
        playing = Playing(ptsUs, atNanos)
    }

    fun read(outLeft: FloatArray, outRight: FloatArray, frameCount: Int) =
        readEndingAt(cursor.get(), outLeft, outRight, frameCount)

    fun readPlaying(outLeft: FloatArray, outRight: FloatArray, frameCount: Int, nowNanos: Long) {
        val ptsUs = playingPtsUs(nowNanos)
        val end = ptsUs?.let(::frameAt) ?: cursor.get()
        readEndingAt(end, outLeft, outRight, frameCount)
    }

    fun lagUs(nowNanos: Long): Long? {
        val newest = newestPtsUs
        val sounding = playingPtsUs(nowNanos) ?: return null
        if (newest == NO_TIME) return null
        return newest - sounding
    }

    fun clear() {
        left.fill(0f)
        right.fill(0f)
        markerCount = 0
        nextInputPtsUs = NO_TIME
        newestPtsUs = NO_TIME
        playing = null
    }

    private fun playingPtsUs(nowNanos: Long): Long? {
        val report = playing ?: return null
        val elapsedUs = ((nowNanos - report.atNanos) / NANOS_PER_MICRO).coerceIn(0, MAX_EXTRAPOLATION_US)
        val newest = newestPtsUs
        val extrapolated = report.ptsUs + elapsedUs
        return if (newest == NO_TIME) extrapolated else minOf(extrapolated, newest)
    }

    private fun frameAt(ptsUs: Long): Long? {
        val count = markerCount
        if (count == 0) return null
        val oldest = maxOf(0, count - MARKERS)
        for (index in count - 1 downTo oldest) {
            val slot = index % MARKERS
            if (markerPtsUs[slot] <= ptsUs || index == oldest) {
                val offset = maxOf(0L, ptsUs - markerPtsUs[slot]) * markerRate[slot] / MICROS_PER_SECOND
                return minOf(markerFrame[slot] + offset, cursor.get())
            }
        }
        return null
    }

    private fun readEndingAt(end: Long, outLeft: FloatArray, outRight: FloatArray, frameCount: Int) {
        require(frameCount <= capacityFrames) { "asked for $frameCount frames, ring holds $capacityFrames" }
        val newest = cursor.get()
        val clampedEnd = end.coerceIn(minOf(newest, frameCount.toLong()), newest).coerceAtLeast(
            newest - capacityFrames + frameCount,
        )
        var position = clampedEnd - frameCount
        for (i in 0 until frameCount) {
            val slot = (position and mask).toInt()
            outLeft[i] = left[slot]
            outRight[i] = right[slot]
            position++
        }
    }

    companion object {
        const val DEFAULT_CAPACITY_FRAMES = 131_072
        const val MARKERS = 1_024
        const val MAX_EXTRAPOLATION_US = 500_000L
        private const val NO_TIME = Long.MIN_VALUE
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L
    }
}
