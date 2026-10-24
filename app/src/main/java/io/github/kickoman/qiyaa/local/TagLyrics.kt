package io.github.kickoman.qiyaa.local

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment

@UnstableApi
object TagLyrics {
    private const val USLT = "USLT"
    private const val ULT = "ULT"
    private const val VORBIS_LYRICS = "LYRICS"

    fun textOf(entries: List<Metadata.Entry>): String? = entries.firstNotNullOfOrNull { entry ->
        when (entry) {
            is BinaryFrame -> if (entry.id == USLT ||
                entry.id == ULT
            ) {
                LocalLyricsFiles.usltText(entry.data)
            } else {
                null
            }

            is TextInformationFrame -> if (entry.id == USLT) entry.values.firstOrNull() else null

            is VorbisComment -> if (entry.key.equals(VORBIS_LYRICS, ignoreCase = true)) entry.value else null

            else -> null
        }
    }

    fun entriesOf(metadata: Metadata?): List<Metadata.Entry> =
        metadata?.let { found -> List(found.length()) { found.get(it) } }.orEmpty()
}
