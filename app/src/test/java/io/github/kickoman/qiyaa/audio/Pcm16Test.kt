package io.github.kickoman.qiyaa.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16Test {
    @Test
    fun `decode maps 16-bit samples onto minus one to one`() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(0).putShort(16384).putShort(-32768).putShort(32767)
        input.flip()
        val out = FloatArray(4)
        Pcm16.decode(input.asShortBuffer(), 4, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0.5f, out[1], 0f)
        assertEquals(-1f, out[2], 0f)
        assertEquals(32767 / 32768f, out[3], 0f)
    }

    @Test
    fun `encode clips values outside minus one to one`() {
        val out = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        Pcm16.encode(floatArrayOf(0f, 0.5f, -2f, 2f), 4, out)
        out.flip()
        assertEquals(0, out.getShort().toInt())
        assertEquals(16383, out.getShort().toInt())
        assertEquals(-32768, out.getShort().toInt())
        assertEquals(32767, out.getShort().toInt())
    }
}
