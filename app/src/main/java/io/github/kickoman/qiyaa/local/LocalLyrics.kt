package io.github.kickoman.qiyaa.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.inspector.MetadataRetriever
import io.github.kickoman.qiyaa.lyrics.Lyrics
import io.github.kickoman.qiyaa.lyrics.LyricsAnswer
import io.github.kickoman.qiyaa.lyrics.LyricsKind
import io.github.kickoman.qiyaa.lyrics.LyricsSource
import io.github.kickoman.qiyaa.lyrics.LyricsText
import io.github.kickoman.qiyaa.track.Track
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

class LocalLyrics(private val context: Context) : LyricsSource {
    private data class Location(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val volume: String?,
        val relativePath: String?,
        val path: String?,
    )

    override suspend fun lyrics(track: Track): LyricsAnswer {
        val id = track.id.toLongOrNull() ?: return LyricsAnswer.Missing()
        return withContext(Dispatchers.IO) {
            try {
                val location = locate(id) ?: return@withContext LyricsAnswer.Missing()
                val lrc = readLrc(location)?.let { LyricsText.parse(LocalLyricsFiles.decodeLrc(it)) }
                if (lrc?.kind == LyricsKind.SYNCED) return@withContext LocalLyricsFiles.choose(lrc, null)
                LocalLyricsFiles.choose(lrc, readTags(location))
            } catch (ignored: IOException) {
                LyricsAnswer.Missing()
            } catch (ignored: SecurityException) {
                LyricsAnswer.Missing()
            }
        }
    }

    private fun locate(id: Long): Location? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(DISPLAY_NAME, MIME_TYPE, VOLUME_NAME, RELATIVE_PATH)
            } else {
                arrayOf(DISPLAY_NAME, MIME_TYPE, DATA)
            }
        val selection = "${MediaStore.Audio.Media._ID} = ?"
        context.contentResolver.query(
            collection,
            columns,
            selection,
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            fun text(column: String): String? =
                cursor.getColumnIndex(column).takeIf { it >= 0 }?.let(cursor::getString)
            return Location(
                uri = ContentUris.withAppendedId(collection, id),
                name = text(DISPLAY_NAME).orEmpty(),
                mimeType = text(MIME_TYPE),
                volume = text(VOLUME_NAME),
                relativePath = text(RELATIVE_PATH),
                path = text(DATA),
            )
        }
        return null
    }

    private fun readLrc(location: Location): ByteArray? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> lrcFromMediaStore(location)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> null
        else -> lrcFromFolder(location)
    }

    private fun lrcFromMediaStore(location: Location): ByteArray? {
        val volume = location.volume ?: return null
        val files = MediaStore.Files.getContentUri(volume)
        val selection = "$RELATIVE_PATH = ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val arguments = arrayOf(location.relativePath.orEmpty(), MEDIA_TYPE_SUBTITLE.toString())
        val found = HashMap<String, Long>()
        val columns = arrayOf(MediaStore.Files.FileColumns._ID, DISPLAY_NAME)
        context.contentResolver.query(files, columns, selection, arguments, null)?.use { cursor ->
            while (cursor.moveToNext()) found[cursor.getString(1).orEmpty()] = cursor.getLong(0)
        }
        val name = LocalLyricsFiles.lrcNameFor(location.name, found.keys.toList()) ?: return null
        val uri = ContentUris.withAppendedId(files, found.getValue(name))
        return context.contentResolver.openInputStream(uri)?.use(::readLimited)
    }

    private fun lrcFromFolder(location: Location): ByteArray? {
        val audio = File(location.path ?: return null)
        val folder = audio.parentFile ?: return null
        val names = folder.list()?.toList().orEmpty()
        val name = LocalLyricsFiles.lrcNameFor(audio.name, names) ?: return null
        val lrc = File(folder, name)
        if (lrc.length() > LyricsText.MAX_BYTES) return null
        return lrc.inputStream().use(::readLimited)
    }

    private fun readLimited(input: InputStream): ByteArray? =
        LocalLyricsFiles.readLimited(input, LyricsText.MAX_BYTES)

    @OptIn(UnstableApi::class)
    private suspend fun readTags(location: Location): Lyrics? {
        val text =
            if (location.mimeType?.lowercase() in WAV_TYPES) {
                val chunk = context.contentResolver.openInputStream(
                    location.uri,
                )?.use(LocalLyricsFiles::riffId3)
                chunk?.let { TagLyrics.textOf(TagLyrics.entriesOf(Id3Decoder().decode(it, it.size))) }
            } else {
                MetadataRetriever.Builder(context, MediaItem.fromUri(location.uri)).build().use { retriever ->
                    val groups = retriever.retrieveTrackGroups().await()
                    val entries =
                        (0 until groups.length).flatMap { group ->
                            val formats = groups.get(group)
                            (0 until formats.length).flatMap {
                                TagLyrics.entriesOf(formats.getFormat(it).metadata)
                            }
                        }
                    TagLyrics.textOf(entries)
                }
            }
        return text?.let(LyricsText::parse)
    }

    private companion object {
        const val DISPLAY_NAME = "_display_name"
        const val MIME_TYPE = "mime_type"
        const val VOLUME_NAME = "volume_name"
        const val RELATIVE_PATH = "relative_path"
        const val DATA = "_data"
        const val MEDIA_TYPE_SUBTITLE = 5
        val WAV_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    }
}
