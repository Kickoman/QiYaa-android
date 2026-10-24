package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsFrom
import io.github.kickoman.qiyaa.lyrics.LyricsSource
import io.github.kickoman.qiyaa.lyrics.LyricsText
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.NetworkException
import io.github.kickoman.qiyaa.yandex.YandexException

class YandexLyrics(
    private val fetch: suspend (trackId: String) -> String?,
    private val signedIn: () -> Boolean,
) : LyricsSource {
    override suspend fun lyrics(track: Track): LyricsAnswer {
        if (!signedIn()) return LyricsAnswer.NoAccount
        val text =
            try {
                fetch(track.id)
            } catch (failed: YandexException) {
                val retryable =
                    failed is NetworkException || (failed is HttpException && failed.status >= SERVER_ERROR)
                return if (retryable) LyricsAnswer.Failed else LyricsAnswer.Missing()
            }
        val lyrics = text?.let(LyricsText::plain) ?: return LyricsAnswer.Missing()
        return LyricsAnswer.Text(lyrics, LyricsFrom.CATALOG)
    }

    private companion object {
        const val SERVER_ERROR = 500
    }
}
