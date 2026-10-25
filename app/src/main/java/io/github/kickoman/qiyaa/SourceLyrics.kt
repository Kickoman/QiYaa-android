package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsSource
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

class SourceLyrics(private val yandex: LyricsSource, private val local: LyricsSource) : LyricsSource {
    override suspend fun lyrics(track: Track): LyricsAnswer = when (track.source) {
        TrackSource.YANDEX -> yandex.lyrics(track)
        TrackSource.LOCAL -> local.lyrics(track)
        TrackSource.SPOTIFY -> LyricsAnswer.Missing()
    }
}
