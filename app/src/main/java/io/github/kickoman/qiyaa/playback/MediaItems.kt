package io.github.kickoman.qiyaa.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

object MediaItems {
    const val SCHEME = "qiyaa"
    const val PARAM_REVISION = "r"
    private const val EXTRA_ID = "id"
    private const val EXTRA_ALBUM_ID = "albumId"
    private const val EXTRA_DURATION_MS = "durationMs"
    private const val EXTRA_ARTISTS = "artists"
    private const val EXTRA_COVER_URL = "cover"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BITRATE_KBPS = "kbps"
    private const val LOCAL_PREFIX = "local:"
    private const val LOCAL_URI = "content://media/external/audio/media/"

    fun uriText(trackId: String, revision: Int = 0): String {
        require(revision >= 0) { "The revision of track $trackId is $revision" }
        val plain = "$SCHEME://track/$trackId"
        return if (revision == 0) plain else "$plain?$PARAM_REVISION=$revision"
    }

    fun uriTextFor(track: Track): String = when (track.source) {
        TrackSource.YANDEX -> uriText(track.id)
        TrackSource.LOCAL -> LOCAL_URI + track.id
    }

    fun mediaIdOf(track: Track): String = when (track.source) {
        TrackSource.YANDEX -> track.id
        TrackSource.LOCAL -> LOCAL_PREFIX + track.id
    }

    fun sourceOf(mediaId: String): TrackSource =
        if (mediaId.startsWith(LOCAL_PREFIX)) TrackSource.LOCAL else TrackSource.YANDEX

    fun idOf(mediaId: String): String = mediaId.removePrefix(LOCAL_PREFIX)

    fun revised(item: MediaItem, trackId: String, revision: Int): MediaItem =
        item.buildUpon().setUri(uriText(trackId, revision)).build()

    fun trackIdOf(uri: Uri): String? = if (uri.scheme == SCHEME) uri.lastPathSegment else null

    fun toMediaItem(track: Track): MediaItem {
        val extras =
            Bundle().apply {
                putString(EXTRA_ID, track.id)
                putString(EXTRA_ALBUM_ID, track.albumId)
                putLong(EXTRA_DURATION_MS, track.durationMs)
                putStringArrayList(EXTRA_ARTISTS, ArrayList(track.artists))
                putString(EXTRA_COVER_URL, track.coverUrl)
                putString(EXTRA_TITLE, track.title)
                putInt(EXTRA_BITRATE_KBPS, track.bitrateKbps)
            }
        val metadata =
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artistLine)
                .setArtworkUri(track.coverUrl?.toUri())
                .setExtras(extras)
                .build()
        return MediaItem.Builder()
            .setMediaId(mediaIdOf(track))
            .setUri(uriTextFor(track))
            .setMediaMetadata(metadata)
            .build()
    }

    fun toTrack(item: MediaItem): Track {
        val extras = item.mediaMetadata.extras ?: Bundle()
        return Track(
            id = extras.getString(EXTRA_ID) ?: idOf(item.mediaId),
            title = extras.getString(EXTRA_TITLE) ?: item.mediaMetadata.title?.toString().orEmpty(),
            artists = extras.getStringArrayList(EXTRA_ARTISTS) ?: emptyList(),
            albumId = extras.getString(EXTRA_ALBUM_ID).orEmpty(),
            durationMs = extras.getLong(EXTRA_DURATION_MS),
            coverUrl = extras.getString(EXTRA_COVER_URL),
            source = sourceOf(item.mediaId),
            bitrateKbps = extras.getInt(EXTRA_BITRATE_KBPS),
        )
    }
}
