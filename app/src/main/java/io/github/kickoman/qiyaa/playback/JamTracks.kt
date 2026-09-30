package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.JamTrack
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.TrackParsing

/** The app's `Track` and the protocol's `JamTrack` (`spec/jam/protocol`, "Tracks"). */
object JamTracks {
    const val MAX_ARTISTS = 10
    const val MAX_NAME = 64
    const val MAX_TITLE = 150
    const val MAX_COVER = 300
    const val MAX_DURATION_MS = 86_400_000L
    private val CATALOG_ID = Regex("^[0-9A-Za-z_-]{1,64}$")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")

    /** null for a track the protocol cannot carry: an id of another shape or no title. */
    fun toJam(track: Track): JamTrack? {
        if (!CATALOG_ID.matches(track.id)) return null
        val title = cut(clean(track.title), MAX_TITLE)
        if (title.isBlank()) return null
        val artists = track.artists.map { cut(clean(it), MAX_NAME) }.filter(String::isNotBlank)
        return JamTrack(
            id = track.id,
            albumId = track.albumId.takeIf(CATALOG_ID::matches),
            title = title,
            artists = artists.take(MAX_ARTISTS),
            durationMs = track.durationMs.coerceIn(0, MAX_DURATION_MS),
            coverUri = coverUri(track.coverUrl),
        )
    }

    fun fromJam(track: JamTrack): Track = Track(
        id = track.id,
        title = track.title,
        artists = track.artists,
        albumId = track.albumId.orEmpty(),
        durationMs = track.durationMs,
        coverUrl = track.coverUri?.let { TrackParsing.coverUrl(it) },
    )

    /** Back from the URL `TrackParsing.coverUrl` built to the Yandex template: no scheme, `%%` for the size. */
    fun coverUri(coverUrl: String?): String? {
        if (coverUrl == null) return null
        val bare = coverUrl.removePrefix("https://").removePrefix("http://")
        val size = TrackParsing.COVER_SIZE
        val template = if (bare.endsWith(size)) bare.dropLast(size.length) + "%%" else bare
        return template.takeIf { "%%" in it && it.length <= MAX_COVER && !CONTROL.containsMatchIn(it) }
    }

    private fun clean(text: String): String = text.replace(CONTROL, " ").trim()

    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end).trimEnd()
    }
}
