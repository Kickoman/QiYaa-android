package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ShannonTest {
    @Test
    fun `every case of spec spotify shannon json gives its bytes and MACs`() {
        SpotifyVectors.forEachCase("shannon") { case ->
            val cipher = Shannon(case.hex("key"))
            for (step in case.list("steps")) {
                when (val call = step.text("call")) {
                    "nonce" -> cipher.nonce(step.long("value").toInt())

                    "encrypt" -> assertEquals(
                        step.text("output"),
                        step.hex("input").also(cipher::encrypt).toHex(),
                    )

                    "decrypt" -> assertEquals(
                        step.text("output"),
                        step.hex("input").also(cipher::decrypt).toHex(),
                    )

                    "finish" -> {
                        val mac = step.text("mac")
                        assertEquals(mac, cipher.finish(mac.length / 2).toHex())
                    }

                    else -> fail("unknown call $call")
                }
            }
        }
    }

    @Test
    fun `a range of a larger array is processed in place and nothing around it changes`() {
        val key = ByteArray(32) { it.toByte() }
        val expected = Shannon(key).run {
            nonce(1)
            val packet = "1097369f33bec9959eb9656da89b6edc8fc358".fromHex()
            decrypt(packet)
            packet.toHex() + finish().toHex()
        }
        val buffer = "aaaa".fromHex() + "1097369f33bec9959eb9656da89b6edc8fc358".fromHex() + "bbbb".fromHex()
        val cipher = Shannon(key)
        cipher.nonce(1)
        cipher.decrypt(buffer, 2, 3)
        cipher.decrypt(buffer, 5, 16)
        assertEquals("aaaa" + expected.dropLast(8) + "bbbb", buffer.toHex())
        assertEquals(expected.takeLast(8), cipher.finish().toHex())
    }

    @Test
    fun `a MAC of any length is cut from whole words`() {
        val key = ByteArray(32) { it.toByte() }
        val long = Shannon(key).apply { nonce(0) }.finish(10)
        val short = Shannon(key).apply { nonce(0) }.finish(4)
        assertEquals(short.toHex(), long.copyOf(4).toHex())
        assertEquals(10, long.size)
    }

    private fun String.fromHex(): ByteArray = checkNotNull(bytesOfHex(this))
}
