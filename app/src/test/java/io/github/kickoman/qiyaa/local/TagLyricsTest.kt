package io.github.kickoman.qiyaa.local

import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagLyricsTest {
    @Test
    fun `LYR-10 the first USLT of an ID3 tag gives the text, whatever its description`() {
        val entries =
            listOf(
                TextInformationFrame("TIT2", null, listOf("Title")),
                BinaryFrame("USLT", uslt("Chorus", "first")),
                BinaryFrame("USLT", uslt("", "second")),
            )
        assertEquals("first", TagLyrics.textOf(entries))
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

    private fun uslt(description: String, text: String): ByteArray =
        byteArrayOf(3) + "eng".toByteArray() + description.toByteArray() + byteArrayOf(0) + text.toByteArray()
}
