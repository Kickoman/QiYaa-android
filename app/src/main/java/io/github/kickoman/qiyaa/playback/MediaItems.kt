package io.github.kickoman.qiyaa.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.kickoman.qiyaa.yandex.Track

/** Track ↔ MediaItem. The URI is virtual; [TrackResolver] turns it into the signed mp3 link. */
object MediaItems {
    const val SCHEME = "qiyaa"
    private const val K_ID = "id"
    private const val K_ALBUM = "albumId"
    private const val K_DURATION = "durationMs"
    private const val K_ARTISTS = "artists"
    private const val K_COVER = "cover"
    private const val K_TITLE = "title"

    fun uriFor(track: Track): Uri = Uri.parse("$SCHEME://track/${track.id}")

    fun trackIdOf(uri: Uri): String? = if (uri.scheme == SCHEME) uri.lastPathSegment else null

    fun toMediaItem(track: Track): MediaItem {
        val extras = Bundle().apply {
            putString(K_ID, track.id)
            putString(K_ALBUM, track.albumId)
            putLong(K_DURATION, track.durationMs)
            putStringArrayList(K_ARTISTS, ArrayList(track.artists))
            putString(K_COVER, track.coverUrl)
            putString(K_TITLE, track.title)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistLine)
            .setArtworkUri(track.coverUrl?.let { Uri.parse(it) })
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriFor(track))
            .setMediaMetadata(meta)
            .build()
    }

    fun toTrack(item: MediaItem): Track {
        val e = item.mediaMetadata.extras ?: Bundle()
        return Track(
            id = e.getString(K_ID) ?: item.mediaId,
            title = e.getString(K_TITLE) ?: item.mediaMetadata.title?.toString().orEmpty(),
            artists = e.getStringArrayList(K_ARTISTS) ?: emptyList(),
            albumId = e.getString(K_ALBUM).orEmpty(),
            durationMs = e.getLong(K_DURATION),
            coverUrl = e.getString(K_COVER),
        )
    }
}
