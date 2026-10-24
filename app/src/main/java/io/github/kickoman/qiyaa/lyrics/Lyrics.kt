package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.track.Track

enum class LyricsKind { SYNCED, PLAIN }

data class LyricsLine(val ms: Long?, val text: String)

data class Lyrics(val kind: LyricsKind, val lines: List<LyricsLine>)

enum class LyricsFrom { CATALOG, LRC_FILE, FILE_TAGS, THIRD_PARTY }

sealed interface LyricsAnswer {
    data class Text(val lyrics: Lyrics, val from: LyricsFrom) : LyricsAnswer

    data class Missing(val serverError: Boolean = false) : LyricsAnswer

    data object Instrumental : LyricsAnswer

    data object Failed : LyricsAnswer

    data object NoAccount : LyricsAnswer
}

fun interface LyricsSource {
    suspend fun lyrics(track: Track): LyricsAnswer
}
