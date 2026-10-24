package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.lyrics.Lyrics
import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsFrom
import io.github.kickoman.qiyaa.lyrics.LyricsKind
import io.github.kickoman.qiyaa.lyrics.LyricsLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLyricsFilesTest {
    private val synced = Lyrics(LyricsKind.SYNCED, listOf(LyricsLine(1_000, "one")))
    private val plain = Lyrics(LyricsKind.PLAIN, listOf(LyricsLine(null, "words")))
    private val otherPlain = Lyrics(LyricsKind.PLAIN, listOf(LyricsLine(null, "other")))

    @Test
    fun `LYR-10 the lrc file has the audio's name, with lrc in any case, the first by name`() {
        val names = listOf("song.lrc", "Song.lrc", "Song.LRC", "Other.lrc", "Song.flac.lrc", "Song.txt")
        assertEquals("Song.LRC", LocalLyricsFiles.lrcNameFor("Song.flac", names))
        assertEquals("Song.flac.lrc", LocalLyricsFiles.lrcNameFor("Song.flac.mp3", names))
        assertNull(LocalLyricsFiles.lrcNameFor("Absent.mp3", names))
    }

    @Test
    fun `LYR-10 an lrc is UTF-8 or UTF-16 with a byte order mark, else UTF-8, else windows-1251`() {
        val text = "Над рекою туман"
        assertEquals(text, decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8(text)))
        assertEquals(
            text,
            decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals(
            text,
            decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)),
        )
        assertEquals(text, decode(utf8(text)))
        assertEquals(text, decode(text.toByteArray(Charset.forName("windows-1251"))))
    }

    @Test
    fun `LYR-10 USLT gives its text in each of the four encodings, whatever its description`() {
        val lines = "Line one\nЛінія два"
        assertEquals(lines, LocalLyricsFiles.usltText(uslt(3, utf8("desc") + 0, utf8(lines))))
        val latin = "Line one\nline two"
        assertEquals(
            latin,
            LocalLyricsFiles.usltText(uslt(0, byteArrayOf(0), latin.toByteArray(Charsets.ISO_8859_1))),
        )
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        assertEquals(
            lines,
            LocalLyricsFiles.usltText(
                uslt(
                    1,
                    bom + "d".toByteArray(Charsets.UTF_16BE) + 0 + 0,
                    bom + lines.toByteArray(Charsets.UTF_16BE),
                ),
            ),
        )
        assertEquals(
            lines,
            LocalLyricsFiles.usltText(uslt(2, byteArrayOf(0, 0), lines.toByteArray(Charsets.UTF_16BE))),
        )
    }

    @Test
    fun `LYR-10 a USLT frame that is cut short or has an unknown encoding has no text`() {
        assertNull(LocalLyricsFiles.usltText(byteArrayOf(3, 'e'.code.toByte())))
        assertNull(LocalLyricsFiles.usltText(uslt(4, byteArrayOf(0), utf8("text"))))
        assertNull(LocalLyricsFiles.usltText(byteArrayOf(3) + utf8("eng") + utf8("no terminator")))
    }

    @Test
    fun `LYR-10 the id3 chunk of a WAV file is found past others, with their padding`() {
        val tag = utf8("ID3-tag-bytes")
        val wav = riff(chunk("fmt ", ByteArray(16)), chunk("data", ByteArray(3)), chunk("id3 ", tag))
        assertArrayEquals(tag, LocalLyricsFiles.riffId3(ByteArrayInputStream(wav)))
        val upper = riff(chunk("ID3 ", tag))
        assertArrayEquals(tag, LocalLyricsFiles.riffId3(ByteArrayInputStream(upper)))
    }

    @Test
    fun `LYR-11 a WAV without an id3 chunk, not a WAV, or with a chunk over 64 MiB has no tag`() {
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(riff(chunk("data", ByteArray(4))))))
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(utf8("ID3 not a riff file"))))
        val huge = riff() + utf8("id3 ") + le(LocalLyricsFiles.MAX_TAG_CHUNK_BYTES + 1)
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(huge)))
    }

    @Test
    fun `LYR-11 an lrc over the limit is not read`() {
        assertEquals(10, LocalLyricsFiles.readLimited(ByteArrayInputStream(ByteArray(10)), 10)?.size)
        assertNull(LocalLyricsFiles.readLimited(ByteArrayInputStream(ByteArray(11)), 10))
    }

    @Test
    fun `LYR-10 a synced lrc first, then the tags, then a plain lrc`() {
        assertEquals(LyricsAnswer.Text(synced, LyricsFrom.LRC_FILE), LocalLyricsFiles.choose(synced, plain))
        assertEquals(LyricsAnswer.Text(synced, LyricsFrom.FILE_TAGS), LocalLyricsFiles.choose(plain, synced))
        assertEquals(
            LyricsAnswer.Text(otherPlain, LyricsFrom.FILE_TAGS),
            LocalLyricsFiles.choose(plain, otherPlain),
        )
        assertEquals(LyricsAnswer.Text(plain, LyricsFrom.LRC_FILE), LocalLyricsFiles.choose(plain, null))
        assertEquals(LyricsAnswer.Missing(), LocalLyricsFiles.choose(null, null))
    }

    private fun decode(bytes: ByteArray): String = LocalLyricsFiles.decodeLrc(bytes)

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun uslt(encoding: Int, description: ByteArray, text: ByteArray): ByteArray =
        byteArrayOf(encoding.toByte()) + utf8("eng") + description + text

    private fun le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun chunk(id: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(utf8(id))
        out.write(le(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(utf8("WAVE")) { all, next -> all + next }
        return utf8("RIFF") + le(body.size) + body
    }

    private operator fun ByteArray.plus(value: Int): ByteArray = this + byteArrayOf(value.toByte())
}
