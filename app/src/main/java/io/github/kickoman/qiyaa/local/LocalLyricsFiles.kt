package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.lyrics.Lyrics
import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsFrom
import io.github.kickoman.qiyaa.lyrics.LyricsKind
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Uslt(val description: String, val text: String)

object LocalLyricsFiles {
    const val MAX_TAG_CHUNK_BYTES = 64 * 1024 * 1024
    private const val LRC = ".lrc"
    private const val READ_CHUNK = 8 * 1024
    private val FALLBACK: Charset = Charset.forName("windows-1251")
    private val UTF_16_BE: Charset = Charsets.UTF_16BE
    private val UTF_16_LE: Charset = Charsets.UTF_16LE
    private val UTF_32_BE: Charset = Charset.forName("UTF-32BE")
    private val UTF_32_LE: Charset = Charset.forName("UTF-32LE")

    fun lrcNameFor(audioName: String, names: List<String>): String? {
        val base = audioName.substringBeforeLast('.', audioName)
        return names.filter {
            it.endsWith(LRC, ignoreCase = true) && it.dropLast(LRC.length) == base
        }.minOrNull()
    }

    fun decodeLrc(bytes: ByteArray): String = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> String(bytes, 4, bytes.size - 4, UTF_32_LE)
        bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> String(bytes, 4, bytes.size - 4, UTF_32_BE)
        bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, UTF_16_LE)
        bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, UTF_16_BE)
        else -> strictUtf8(bytes) ?: String(bytes, FALLBACK)
    }

    fun uslt(frame: ByteArray): Uslt? {
        if (frame.size < 4) return null
        val charset =
            when (frame[0].toInt()) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16
                2 -> UTF_16_BE
                3 -> Charsets.UTF_8
                else -> return null
            }
        val wide = charset != Charsets.ISO_8859_1 && charset != Charsets.UTF_8
        val textStart = endOfString(frame, 4, wide) ?: return null
        val terminator = if (wide) 2 else 1
        val description = String(frame, 4, textStart - terminator - 4, charset)
        return Uslt(description, String(frame, textStart, frame.size - textStart, charset).trimEnd('\u0000'))
    }

    fun riffId3(input: InputStream): ByteArray? {
        val header = input.readExactly(12) ?: return null
        if (!header.startsWith('R', 'I', 'F', 'F') || header.ascii(8, 4) != "WAVE") return null
        while (true) {
            val chunk = input.readExactly(8) ?: return null
            val id = chunk.ascii(0, 4)
            val size =
                ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            if (id == "id3 " || id == "ID3 ") {
                return if (size > MAX_TAG_CHUNK_BYTES) null else input.readGrowing(size.toInt())
            }
            if (!input.skipExactly(size + (size and 1))) return null
        }
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) return bytes.toByteArray()
            bytes.write(chunk, 0, count)
            if (bytes.size() > limit) return null
        }
    }

    fun choose(lrc: Lyrics?, tags: Lyrics?): LyricsAnswer = when {
        lrc?.kind == LyricsKind.SYNCED -> LyricsAnswer.Text(lrc, LyricsFrom.LRC_FILE)
        tags != null -> LyricsAnswer.Text(tags, LyricsFrom.FILE_TAGS)
        lrc != null -> LyricsAnswer.Text(lrc, LyricsFrom.LRC_FILE)
        else -> LyricsAnswer.Missing()
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (ignored: CharacterCodingException) {
        null
    }

    private fun endOfString(frame: ByteArray, from: Int, wide: Boolean): Int? {
        if (!wide) {
            val end = (from until frame.size).firstOrNull { frame[it].toInt() == 0 } ?: return null
            return end + 1
        }
        var at = from
        while (at + 1 < frame.size) {
            if (frame[at].toInt() == 0 && frame[at + 1].toInt() == 0) return at + 2
            at += 2
        }
        return null
    }

    private fun InputStream.readExactly(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = read(bytes, read, size - read)
            if (count < 0) return null
            read += count
        }
        return bytes
    }

    private fun InputStream.readGrowing(size: Int): ByteArray? {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK)
        while (bytes.size() < size) {
            val count = read(chunk, 0, minOf(chunk.size, size - bytes.size()))
            if (count < 0) return null
            bytes.write(chunk, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun InputStream.skipExactly(size: Long): Boolean {
        var left = size
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else if (read() < 0) {
                return false
            } else {
                left--
            }
        }
        return true
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it].toInt() and 0xFF == values[it] }

    private fun ByteArray.startsWith(vararg chars: Char): Boolean =
        size >= chars.size && chars.indices.all { this[it].toInt() == chars[it].code }

    private fun ByteArray.ascii(from: Int, length: Int): String =
        String(this, from, length, Charsets.US_ASCII)
}
