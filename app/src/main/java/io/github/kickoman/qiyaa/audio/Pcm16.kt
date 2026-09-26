package io.github.kickoman.qiyaa.audio

import java.nio.ByteBuffer
import java.nio.ShortBuffer

object Pcm16 {
    const val BYTES_PER_SAMPLE = 2
    private const val SCALE = 32768f
    private const val MAX = 32767
    private const val MIN = -32768

    fun decode(input: ShortBuffer, sampleCount: Int, out: FloatArray) {
        for (i in 0 until sampleCount) out[i] = input.get(i) / SCALE
    }

    fun encode(samples: FloatArray, sampleCount: Int, out: ByteBuffer) {
        for (i in 0 until sampleCount) {
            val value = (samples[i] * MAX).toInt().coerceIn(MIN, MAX)
            out.putShort(value.toShort())
        }
    }
}
