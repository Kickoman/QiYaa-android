package io.github.kickoman.qiyaa.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of src/audio/VisTap.h: a lock-free stereo ring of the latest PCM for the visualizer.
 * The audio thread writes, the UI thread reads; torn reads are tolerated by design.
 */
class VisTap(val capacityFrames: Int = 4096) {
    init {
        require(capacityFrames > 0 && capacityFrames and (capacityFrames - 1) == 0) { "capacity must be a power of two" }
    }

    private val left = FloatArray(capacityFrames)
    private val right = FloatArray(capacityFrames)
    private val cursor = AtomicInteger(0)
    private val mask = capacityFrames - 1

    @Volatile
    var sampleRate: Int = 44100

    /** Appends [frameCount] interleaved frames ([channels] = 1 or 2). */
    fun write(frames: FloatArray, frameCount: Int, channels: Int) {
        var pos = cursor.get()
        var p = 0
        for (i in 0 until frameCount) {
            val idx = pos and mask
            val l = frames[p]
            val r = if (channels >= 2) frames[p + 1] else l
            left[idx] = l
            right[idx] = r
            pos++
            p += channels
        }
        cursor.set(pos)
    }

    /** Copies the latest [n] frames (oldest first) into [outL]/[outR]. */
    fun read(outL: FloatArray, outR: FloatArray, n: Int) {
        val end = cursor.get()
        var idx = end - n
        for (i in 0 until n) {
            val k = idx and mask
            outL[i] = left[k]
            outR[i] = right[k]
            idx++
        }
    }

    fun clear() {
        left.fill(0f)
        right.fill(0f)
    }
}
