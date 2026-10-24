package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.lyrics.Lyrics
import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsFrom
import io.github.kickoman.qiyaa.lyrics.LyricsKind
import io.github.kickoman.qiyaa.lyrics.LyricsLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    fun `LYR-10 an lrc is UTF-8, 16 or 32 with a byte order mark, else UTF-8, else windows-1251`() {
        val text = "Над рекою туман"
        assertEquals(text, decode(bytes(0xEF, 0xBB, 0xBF) + utf8(text)))
        assertEquals(text, decode(bytes(0xFF, 0xFE) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, decode(bytes(0xFE, 0xFF) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(text, decode(bytes(0xFF, 0xFE, 0, 0) + text.toByteArray(Charset.forName("UTF-32LE"))))
        assertEquals(text, decode(bytes(0, 0, 0xFE, 0xFF) + text.toByteArray(Charset.forName("UTF-32BE"))))
        assertEquals(text, decode(utf8(text)))
        assertEquals(text, decode(text.toByteArray(Charset.forName("windows-1251"))))
    }

    @Test
    fun `LYR-10 USLT gives its description and text in each of the four encodings`() {
        val lines = "Line one\nЛінія два"
        assertEquals(Uslt("desc", lines), LocalLyricsFiles.uslt(uslt(3, utf8("desc") + 0, utf8(lines))))
        val latin = "Line one\nline two"
        assertEquals(
            Uslt("", latin),
            LocalLyricsFiles.uslt(uslt(0, bytes(0), latin.toByteArray(Charsets.ISO_8859_1))),
        )
        val bom = bytes(0xFE, 0xFF)
        val wide = uslt(
            1,
            bom + "d".toByteArray(Charsets.UTF_16BE) + 0 + 0,
            bom + lines.toByteArray(Charsets.UTF_16BE),
        )
        assertEquals(Uslt("d", lines), LocalLyricsFiles.uslt(wide))
        assertEquals(
            Uslt("", lines),
            LocalLyricsFiles.uslt(uslt(2, bytes(0, 0), lines.toByteArray(Charsets.UTF_16BE))),
        )
    }

    @Test
    fun `LYR-10 a UTF-16 description ends at an aligned double zero, and trailing zeros leave the text`() {
        val description = bytes(0x01, 0x00, 0x00, 0x41, 0x00, 0x00)
        val frame = uslt(2, description, "Text".toByteArray(Charsets.UTF_16BE) + 0 + 0)
        assertEquals(Uslt("ĀA", "Text"), LocalLyricsFiles.uslt(frame))
        assertEquals(Uslt("", "words"), LocalLyricsFiles.uslt(uslt(3, bytes(0), utf8("words") + 0)))
    }

    @Test
    fun `LYR-10 a USLT frame that is cut short or has an unknown encoding has no text`() {
        assertNull(LocalLyricsFiles.uslt(bytes(3, 'e'.code)))
        assertNull(LocalLyricsFiles.uslt(uslt(4, bytes(0), utf8("text"))))
        assertNull(LocalLyricsFiles.uslt(bytes(3) + utf8("eng") + utf8("no terminator")))
    }

    @Test
    fun `LYR-10 the id3 chunk of a WAV file is found past others, with their padding`() {
        val tag = utf8("ID3-tag-bytes")
        val wav = riff(chunk("fmt ", ByteArray(16)), chunk("data", ByteArray(3)), chunk("id3 ", tag))
        assertArrayEquals(tag, LocalLyricsFiles.riffId3(ByteArrayInputStream(wav)))
        assertArrayEquals(tag, LocalLyricsFiles.riffId3(ByteArrayInputStream(riff(chunk("ID3 ", tag)))))
    }

    @Test
    fun `LYR-11 a WAV without an id3 chunk, not a WAV, or with a chunk cut short has no tag`() {
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(riff(chunk("data", ByteArray(4))))))
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(utf8("ID3 not a riff file"))))
        val avi = utf8("RIFF") + le(4) + utf8("AVI ") + chunk("id3 ", utf8("tag"))
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(avi)))
        val cut = riff() + utf8("id3 ") + le(1_000) + ByteArray(10)
        assertNull(LocalLyricsFiles.riffId3(ByteArrayInputStream(cut)))
    }

    @Test
    fun `LYR-11 an id3 chunk over 16 MiB is not read at all`() {
        val header = riff() + utf8("id3 ") + le(LocalLyricsFiles.MAX_TAG_CHUNK_BYTES + 1)
        var read = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int {
                    read++
                    return if (read <= header.size) header[(read - 1).toInt()].toInt() and 0xFF else 0
                }
            }
        assertNull(LocalLyricsFiles.riffId3(endless))
        assertEquals(header.size.toLong(), read)
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

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun uslt(encoding: Int, description: ByteArray, text: ByteArray): ByteArray =
        bytes(encoding) + utf8("eng") + description + text

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
