package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AudioFileTest {
    @Test
    fun `every case of spec spotify audio json decrypts as given`() {
        SpotifyVectors.forEachCase("audio") { case ->
            val decryptor = AudioDecryptor(Bytes.of(case.hex("key")))
            assertEquals(AudioFile.IV.hex(), case.text("iv"))
            when (val kind = case.text("kind")) {
                "decrypt" -> {
                    val data = case.hex("encrypted")
                    decryptor.apply(data, case.long("offset"))
                    assertEquals(case.text("decrypted"), data.toHex())
                }

                "header" -> {
                    assertEquals(AudioFile.HEADER_SIZE, case.int("headerSize"))
                    val encrypted = case.hex("encrypted")
                    val file = encrypted.copyOf().also { decryptor.apply(it, 0) }
                    val audio = case.hex("audio")
                    assertEquals(audio.toHex(), file.copyOfRange(AudioFile.HEADER_SIZE, file.size).toHex())
                    for (x in audio.indices) {
                        val byte = byteArrayOf(encrypted[AudioFile.HEADER_SIZE + x])
                        decryptor.apply(byte, AudioFile.HEADER_SIZE.toLong() + x)
                        assertEquals("audio byte $x", audio[x], byte[0])
                    }
                }

                else -> fail("unknown kind $kind")
            }
        }
    }
}
