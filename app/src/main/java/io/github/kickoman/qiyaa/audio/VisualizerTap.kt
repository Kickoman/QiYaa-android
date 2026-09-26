package io.github.kickoman.qiyaa.audio

import java.util.concurrent.atomic.AtomicInteger

class VisualizerTap(val capacityFrames: Int = DEFAULT_CAPACITY_FRAMES) {
    init {
        require(capacityFrames > 0 && capacityFrames and (capacityFrames - 1) == 0) {
            "capacity must be a power of two, got $capacityFrames"
        }
    }

    private val left = FloatArray(capacityFrames)
    private val right = FloatArray(capacityFrames)
    private val cursor = AtomicInteger(0)
    private val mask = capacityFrames - 1

    @Volatile
    var sampleRate: Int = EqualizerDsp.DEFAULT_SAMPLE_RATE

    fun write(frames: FloatArray, frameCount: Int, channels: Int) {
        var position = cursor.get()
        var sampleIndex = 0
        for (i in 0 until frameCount) {
            val slot = position and mask
            val leftSample = frames[sampleIndex]
            left[slot] = leftSample
            right[slot] = if (channels >= 2) frames[sampleIndex + 1] else leftSample
            position++
            sampleIndex += channels
        }
        cursor.set(position)
    }

    fun read(outLeft: FloatArray, outRight: FloatArray, frameCount: Int) {
        require(frameCount <= capacityFrames) { "asked for $frameCount frames, ring holds $capacityFrames" }
        var position = cursor.get() - frameCount
        for (i in 0 until frameCount) {
            val slot = position and mask
            outLeft[i] = left[slot]
            outRight[i] = right[slot]
            position++
        }
    }

    fun clear() {
        left.fill(0f)
        right.fill(0f)
    }

    companion object {
        const val DEFAULT_CAPACITY_FRAMES = 4096
    }
}
