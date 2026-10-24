package io.github.kickoman.qiyaa.local

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.io.InputStream

@OptIn(UnstableApi::class)
object TagLyrics {
    private const val USLT = "USLT"
    private const val ULT = "ULT"
    private const val LYRICS = "LYRICS"

    fun textOf(entries: List<Metadata.Entry>): String? {
        val found = ArrayList<Pair<String, String>>()
        for (entry in entries) {
            when (entry) {
                is BinaryFrame ->
                    if (entry.id == USLT || entry.id == ULT) {
                        LocalLyricsFiles.uslt(entry.data)?.let { found += keyOf(it.description) to it.text }
                    }

                is TextInformationFrame -> if (entry.id ==
                    USLT
                ) {
                    entry.values.firstOrNull()?.let { found += LYRICS to it }
                }

                is VorbisComment -> if (entry.key == LYRICS) found += LYRICS to entry.value
            }
        }
        return found.sortedBy { it.first }.firstOrNull()?.second
    }

    fun wavTagText(input: InputStream): String? {
        val chunk = LocalLyricsFiles.riffId3(input) ?: return null
        val metadata =
            try {
                Id3Decoder(::isLyricsFrame).decode(chunk, chunk.size)
            } catch (ignored: IllegalArgumentException) {
                return null // A tag that claims more bytes than its chunk holds.
            }
        return textOf(entriesOf(metadata))
    }

    fun entriesOf(metadata: Metadata?): List<Metadata.Entry> =
        metadata?.let { found -> List(found.length()) { found.get(it) } }.orEmpty()

    private fun keyOf(description: String): String {
        val upper = description.map { if (it in 'a'..'z') it - ('a' - 'A') else it }.joinToString("")
        return if (upper.isEmpty() || upper == LYRICS) LYRICS else "$LYRICS:$upper"
    }

    private fun isLyricsFrame(majorVersion: Int, id0: Int, id1: Int, id2: Int, id3: Int): Boolean =
        if (majorVersion == 2) {
            id0 == 'U'.code && id1 == 'L'.code && id2 == 'T'.code
        } else {
            id0 == 'U'.code && id1 == 'S'.code && id2 == 'L'.code && id3 == 'T'.code
        }
}
