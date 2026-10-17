package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class QueueSnapshot(
    val tracks: List<Track>,
    val batchIds: List<String>,
    val title: String,
    val sourceId: String?,
    val isWave: Boolean,
    val waveSessionId: String,
    val waveStationId: String,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: Boolean,
    val jam: Boolean = false,
    val parts: List<EntryPart> = emptyList(),
    val items: List<ItemMark?> = emptyList(),
    val continueWithVibe: Boolean? = null,
    val history: List<String> = emptyList(),
)

object QueueSnapshotCodec {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: QueueSnapshot): String {
        val defaultPart = EntryPart.defaultFor(snapshot.isWave)
        return json.encodeToString(
            SnapshotFile.serializer(),
            SnapshotFile(
                version = VERSION,
                title = snapshot.title,
                sourceId = snapshot.sourceId,
                isWave = snapshot.isWave,
                waveSessionId = snapshot.waveSessionId,
                waveStationId = snapshot.waveStationId,
                index = snapshot.index,
                positionMs = snapshot.positionMs,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
                jam = snapshot.jam,
                continueWithVibe = snapshot.continueWithVibe,
                history = snapshot.history,
                tracks =
                snapshot.tracks.mapIndexed { i, track ->
                    val part = snapshot.parts.getOrNull(i)
                    val item = snapshot.items.getOrNull(i)
                    TrackEntry(
                        id = track.id,
                        title = track.title,
                        artists = track.artists,
                        albumId = track.albumId,
                        durationMs = track.durationMs,
                        available = track.available,
                        coverUrl = track.coverUrl,
                        batchId = snapshot.batchIds.getOrElse(i) { "" },
                        jamItemId = item?.itemId.orEmpty(),
                        jamAddedBy = item?.addedBy.orEmpty(),
                        jamWave = snapshot.jam && part == EntryPart.CONTINUATION,
                        part = part?.takeIf { it in STORED_PARTS && it != defaultPart }?.key,
                    )
                },
            ),
        )
    }

    fun decode(text: String?): QueueSnapshot? {
        if (text.isNullOrEmpty()) return null
        val file =
            try {
                json.decodeFromString(SnapshotFile.serializer(), text)
            } catch (ignored: SerializationException) {
                return null
            } catch (ignored: IllegalArgumentException) {
                return null
            }
        if (file.version != VERSION) return null
        val tracks = file.tracks.map {
            Track(it.id, it.title, it.artists, it.albumId, it.durationMs, it.available, it.coverUrl)
        }
        val index = if (tracks.isEmpty()) 0 else file.index.coerceIn(0, tracks.lastIndex)
        return QueueSnapshot(
            tracks = tracks,
            batchIds = file.tracks.map { it.batchId },
            title = file.title,
            sourceId = file.sourceId,
            isWave = file.isWave,
            waveSessionId = file.waveSessionId,
            waveStationId = file.waveStationId,
            index = index,
            positionMs = file.positionMs.coerceAtLeast(0),
            shuffle = file.shuffle,
            repeat = file.repeat,
            jam = file.jam,
            parts = partsOf(file),
            items = itemsOf(file, index),
            continueWithVibe = file.continueWithVibe,
            history = file.history,
        )
    }

    private fun partsOf(file: SnapshotFile): List<EntryPart> {
        if (!file.jam && file.tracks.all { it.part == null }) return emptyList()
        val default = EntryPart.defaultFor(file.isWave)
        return file.tracks.map { entry ->
            val stored = EntryPart.fromKey(entry.part)?.takeIf { it in STORED_PARTS } ?: default
            when {
                !file.jam -> stored
                entry.jamItemId.isNotEmpty() -> EntryPart.ITEM
                entry.jamWave || stored == EntryPart.CONTINUATION -> EntryPart.CONTINUATION
                else -> EntryPart.OTHER
            }
        }
    }

    private fun itemsOf(file: SnapshotFile, index: Int): List<ItemMark?> {
        if (!file.jam || file.tracks.all { it.jamItemId.isEmpty() }) return emptyList()
        return file.tracks.mapIndexed { i, entry ->
            entry.jamItemId.ifEmpty { null }?.let { ItemMark(it, entry.jamAddedBy, waiting = i > index) }
        }
    }

    private val STORED_PARTS = setOf(EntryPart.HEAD, EntryPart.CONTINUATION)

    @Serializable
    private data class SnapshotFile(
        val version: Int,
        val title: String,
        val sourceId: String? = null,
        val isWave: Boolean,
        val waveSessionId: String = "",
        val waveStationId: String = "",
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeat: Boolean,
        val jam: Boolean = false,
        val continueWithVibe: Boolean? = null,
        val history: List<String> = emptyList(),
        val tracks: List<TrackEntry>,
    )

    @Serializable
    private data class TrackEntry(
        val id: String,
        val title: String,
        val artists: List<String> = emptyList(),
        val albumId: String = "",
        val durationMs: Long = 0,
        val available: Boolean = true,
        val coverUrl: String? = null,
        val batchId: String = "",
        val jamItemId: String = "",
        val jamAddedBy: String = "",
        val jamWave: Boolean = false,
        val part: String? = null,
    )
}
