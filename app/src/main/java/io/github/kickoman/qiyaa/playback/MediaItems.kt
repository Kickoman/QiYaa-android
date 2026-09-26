package io.github.kickoman.qiyaa.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.kickoman.qiyaa.yandex.Track

object MediaItems {
    const val SCHEME = "qiyaa"
    private const val EXTRA_ID = "id"
    private const val EXTRA_ALBUM_ID = "albumId"
    private const val EXTRA_DURATION_MS = "durationMs"
    private const val EXTRA_ARTISTS = "artists"
    private const val EXTRA_COVER_URL = "cover"
    private const val EXTRA_TITLE = "title"

    fun uriFor(track: Track): Uri = Uri.parse("$SCHEME://track/${track.id}")

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
            }
        val metadata =
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artistLine)
                .setArtworkUri(track.coverUrl?.let { Uri.parse(it) })
                .setExtras(extras)
                .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track))
            .setMediaMetadata(metadata)
            .build()
    }

    fun toTrack(item: MediaItem): Track {
        val extras = item.mediaMetadata.extras ?: Bundle()
        return Track(
            id = extras.getString(EXTRA_ID) ?: item.mediaId,
            title = extras.getString(EXTRA_TITLE) ?: item.mediaMetadata.title?.toString().orEmpty(),
            artists = extras.getStringArrayList(EXTRA_ARTISTS) ?: emptyList(),
            albumId = extras.getString(EXTRA_ALBUM_ID).orEmpty(),
            durationMs = extras.getLong(EXTRA_DURATION_MS),
            coverUrl = extras.getString(EXTRA_COVER_URL),
        )
    }
}
