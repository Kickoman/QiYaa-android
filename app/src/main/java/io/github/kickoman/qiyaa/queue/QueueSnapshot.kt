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
    val jamSlots: List<JamSlot>? = null,
)

object QueueSnapshotCodec {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: QueueSnapshot): String = json.encodeToString(
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
            jam = snapshot.jamSlots != null,
            tracks =
            snapshot.tracks.mapIndexed { i, track ->
                TrackEntry(
                    id = track.id,
                    title = track.title,
                    artists = track.artists,
                    albumId = track.albumId,
                    durationMs = track.durationMs,
                    available = track.available,
                    coverUrl = track.coverUrl,
                    batchId = snapshot.batchIds.getOrElse(i) { "" },
                    jamItemId = (snapshot.jamSlots?.getOrNull(i) as? JamSlot.Item)?.itemId.orEmpty(),
                    jamAddedBy = (snapshot.jamSlots?.getOrNull(i) as? JamSlot.Item)?.addedBy.orEmpty(),
                    jamWave = snapshot.jamSlots?.getOrNull(i) == JamSlot.Wave,
                )
            },
        ),
    )

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
        return QueueSnapshot(
            tracks = tracks,
            batchIds = file.tracks.map { it.batchId },
            title = file.title,
            sourceId = file.sourceId,
            isWave = file.isWave,
            waveSessionId = file.waveSessionId,
            waveStationId = file.waveStationId,
            index = if (tracks.isEmpty()) 0 else file.index.coerceIn(0, tracks.lastIndex),
            positionMs = file.positionMs.coerceAtLeast(0),
            shuffle = file.shuffle,
            repeat = file.repeat,
            jamSlots = if (file.jam) file.tracks.map(::slotOf) else null,
        )
    }

    private fun slotOf(entry: TrackEntry): JamSlot = when {
        entry.jamItemId.isNotEmpty() -> JamSlot.Item(entry.jamItemId, entry.jamAddedBy)
        entry.jamWave -> JamSlot.Wave
        else -> JamSlot.Other
    }

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
    )
}
