package io.github.kickoman.qiyaa.local

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TagLyricsTest {
    @Before
    fun setUp() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }

    @After
    fun tearDown() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(null)
    }

    @Test
    fun `LYR-10 descriptions sort as TagLib keys, upper-cased in ASCII only, LYRICS as none`() {
        val ascii = listOf(BinaryFrame("USLT", uslt("Banana", "b")), BinaryFrame("USLT", uslt("apple", "a")))
        assertEquals("a", TagLyrics.textOf(ascii))
        val cyrillic =
            listOf(BinaryFrame("USLT", uslt("куплет", "k")), BinaryFrame("USLT", uslt("Припев", "p")))
        assertEquals("p", TagLyrics.textOf(cyrillic))
        val named = listOf(BinaryFrame("USLT", uslt("A", "a")), BinaryFrame("USLT", uslt("lyrics", "l")))
        assertEquals("l", TagLyrics.textOf(named))
    }

    @Test
    fun `LYR-10 of several USLT frames the one without a description wins, then descriptions by name`() {
        val entries =
            listOf(
                TextInformationFrame("TIT2", null, listOf("Title")),
                BinaryFrame("USLT", uslt("Chorus", "chorus")),
                BinaryFrame("USLT", uslt("", "plain")),
                BinaryFrame("USLT", uslt("", "second plain")),
            )
        assertEquals("plain", TagLyrics.textOf(entries))
        val described =
            listOf(BinaryFrame("USLT", uslt("verse", "v")), BinaryFrame("USLT", uslt("Chorus", "c")))
        assertEquals("c", TagLyrics.textOf(described))
    }

    @Test
    fun `LYR-10 ULT of ID3v2_2, lyr in MP4 and LYRICS in Vorbis comments give the text too`() {
        assertEquals("v22", TagLyrics.textOf(listOf(BinaryFrame("ULT", uslt("", "v22")))))
        assertEquals("mp4", TagLyrics.textOf(listOf(TextInformationFrame("USLT", null, listOf("mp4")))))
        assertEquals("flac", TagLyrics.textOf(listOf(VorbisComment("lyrics", "flac"))))
        assertNull(
            TagLyrics.textOf(
                listOf(VorbisComment("TITLE", "t"), TextInformationFrame("TIT2", null, listOf("t"))),
            ),
        )
    }

    @Test
    fun `LYR-10 a WAV file's id3 chunk gives its lyrics in ID3v2_2, v2_3 and v2_4`() {
        for (version in listOf(2, 3, 4)) {
            val wav = wav(id3(version, "Line one\nLine two"))
            assertEquals("v2.$version", "Line one\nLine two", TagLyrics.wavTagText(ByteArrayInputStream(wav)))
        }
    }

    @Test
    fun `LYR-10 an id3 chunk whose tag claims more than the chunk holds has no lyrics`() {
        val tag = id3(4, "words")
        val lying = tag.copyOf().also {
            it[6] = 0
            it[7] = 0
            it[8] = 0x1F
            it[9] = 0x20
        }
        assertNull(TagLyrics.wavTagText(ByteArrayInputStream(wav(lying))))
    }

    private fun uslt(description: String, text: String): ByteArray =
        byteArrayOf(3) + "eng".toByteArray() + description.toByteArray() + byteArrayOf(0) + text.toByteArray()

    private fun id3(version: Int, text: String): ByteArray {
        val body = uslt("", text)
        val frame =
            when (version) {
                2 -> "ULT".toByteArray() + byteArrayOf(0, (body.size shr 8).toByte(), body.size.toByte()) +
                    body

                3 -> "USLT".toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() +
                    byteArrayOf(0, 0) +
                    body

                else -> "USLT".toByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
            }
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0) +
            syncsafe(frame.size) + frame
    }

    private fun wav(tag: ByteArray): ByteArray {
        val padded = if (tag.size % 2 == 1) tag + byteArrayOf(0) else tag
        val body = "WAVE".toByteArray() + "id3 ".toByteArray() + le(tag.size) + padded
        return "RIFF".toByteArray() + le(body.size) + body
    }

    private fun le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun syncsafe(size: Int): ByteArray = byteArrayOf(
        (size shr 21 and 0x7F).toByte(),
        (size shr 14 and 0x7F).toByte(),
        (size shr 7 and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )
}
