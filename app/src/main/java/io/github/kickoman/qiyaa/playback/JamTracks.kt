package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.JamCodec
import io.github.kickoman.qiyaa.jam.JamTrack
import io.github.kickoman.qiyaa.jam.JamTrackSource
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.TrackParsing
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The app's `Track` and the protocol's `JamTrack` (`spec/jam/protocol`, "Tracks"). */
object JamTracks {
    const val MAX_ARTISTS = 10
    const val MAX_NAME = 64
    const val MAX_TITLE = 150
    const val MAX_COVER = 300
    const val MAX_DURATION_MS = 86_400_000L
    private const val HMAC = "HmacSHA256"
    private const val HOST_ID_BYTES = 8
    private val CATALOG_ID = Regex("^[0-9A-Za-z_-]{1,64}$")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")

    /** null for a track the protocol cannot carry: not Yandex's, an id of another shape or no title. */
    fun toJam(track: Track): JamTrack? = when (track.source) {
        TrackSource.YANDEX -> catalogTrack(track)
        TrackSource.LOCAL, TrackSource.SPOTIFY -> null
    }

    fun baseTrack(track: Track, hostSecret: String): JamTrack? = when (track.source) {
        TrackSource.YANDEX -> toJam(track)
        TrackSource.LOCAL, TrackSource.SPOTIFY -> hostTrack(track, hostSecret)
    }

    private fun hostTrack(track: Track, hostSecret: String): JamTrack? =
        within(track)?.copy(id = hostTrackId(track, hostSecret), source = JamTrackSource.HOST)

    private fun hostTrackId(track: Track, hostSecret: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(hostSecret.toByteArray(Charsets.UTF_8), HMAC))
        val digest = mac.doFinal("${track.source.key}\n${track.id}".toByteArray(Charsets.UTF_8))
        return digest.take(HOST_ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun catalogTrack(track: Track): JamTrack? {
        if (!CATALOG_ID.matches(track.id)) return null
        return within(track)?.copy(
            id = track.id,
            albumId = track.albumId.takeIf(CATALOG_ID::matches),
            coverUri = coverUri(track.coverUrl),
        )
    }

    private fun within(track: Track): JamTrack? {
        val title = cut(clean(track.title), MAX_TITLE)
        if (title.isBlank()) return null
        val artists = track.artists.map { cut(clean(it), MAX_NAME) }.filter(String::isNotBlank)
        return JamTrack(
            id = "",
            title = title,
            artists = artists.take(MAX_ARTISTS),
            durationMs = track.durationMs.coerceIn(0, MAX_DURATION_MS),
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
        return template.takeIf {
            it.length <= MAX_COVER && JamCodec.isCoverUri(it) && !CONTROL.containsMatchIn(it)
        }
    }

    private fun clean(text: String): String = text.replace(CONTROL, " ").trim()

    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end).trimEnd()
    }
}
