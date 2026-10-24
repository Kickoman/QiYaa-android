package io.github.kickoman.qiyaa.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LocalLibrary(private val context: Context) {
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    val flacDecodes: Boolean by lazy {
        val format = MediaFormat.createAudioFormat(LocalTracks.FLAC, FLAC_PROBE_RATE, 2)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
    }

    private val changed = AtomicBoolean(true)
    private var watching = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun takeChanged(): Boolean {
        if (!watching) {
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) = changed.set(true)
                }
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            watching = true
        }
        return changed.getAndSet(false)
    }

    fun rows(): List<LocalRow> = query(MUSIC, emptyArray()).filter {
        LocalTracks.isTaken(it.mimeType, flacDecodes)
    }

    fun rowsById(ids: List<Long>): Map<Long, LocalRow> = ids.chunked(IDS_PER_QUERY).flatMap { chunk ->
        val marks = chunk.joinToString(",") { "?" }
        query("${MediaStore.Audio.Media._ID} IN ($marks)", chunk.map(Long::toString).toTypedArray())
    }.associateBy(LocalRow::id)

    private fun query(selection: String, arguments: Array<String>): List<LocalRow> {
        val rows = ArrayList<LocalRow>()
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection(),
            selection,
            arguments,
            null,
        )?.use {
            val columns = Columns(it)
            while (it.moveToNext()) rows += columns.read(it)
        }
        return rows
    }

    private fun projection(): Array<String> {
        val columns =
            mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns += MediaStore.Audio.Media.VOLUME_NAME
            columns += MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            columns += MediaStore.Audio.Media.DATA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) columns += MediaStore.Audio.Media.BITRATE
        return columns.toTypedArray()
    }

    private class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        private val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        private val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        private val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        private val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        private val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        private val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        private val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        private val mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        private val volume = cursor.getColumnIndex(VOLUME_NAME)
        private val relativePath = cursor.getColumnIndex(RELATIVE_PATH)
        private val data = cursor.getColumnIndex(DATA)
        private val bitrate = cursor.getColumnIndex(BITRATE)

        fun read(cursor: Cursor): LocalRow = LocalRow(
            id = cursor.getLong(id),
            title = cursor.getString(title),
            displayName = cursor.getString(displayName).orEmpty(),
            artist = cursor.getString(artist),
            album = cursor.getString(album),
            albumId = cursor.getLong(albumId),
            durationMs = cursor.getLong(duration),
            trackNumber = cursor.getInt(track),
            mimeType = cursor.getString(mimeType),
            sizeBytes = cursor.getLong(size),
            bitrateBitsPerSecond = bitrate.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
            folder = folder(cursor),
        )

        private fun folder(cursor: Cursor): LocalFolder {
            if (relativePath >= 0) {
                val path = cursor.getString(relativePath).orEmpty().split('/').filter(String::isNotEmpty)
                return LocalFolder(cursor.getString(volume).orEmpty(), path)
            }
            val parent = cursor.getString(data)?.let { File(it).parent }.orEmpty()
            return LocalFolder("", parent.split('/').filter(String::isNotEmpty))
        }
    }

    private companion object {
        const val MUSIC = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        const val IDS_PER_QUERY = 500
        const val FLAC_PROBE_RATE = 44_100
        const val VOLUME_NAME = "volume_name"
        const val RELATIVE_PATH = "relative_path"
        const val DATA = "_data"
        const val BITRATE = "bitrate"
    }
}
