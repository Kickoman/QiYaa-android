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
    val parts: List<EntryPart> = emptyList(),
    val items: List<ItemMark?> = emptyList(),
    val seedsVersions: List<Int?> = emptyList(),
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
                continueWithVibe = snapshot.continueWithVibe,
                history = snapshot.history,
                tracks =
                    snapshot.tracks.mapIndexed { i, track ->
                        val item = snapshot.items.getOrNull(i)
                        val stored = snapshot.parts.getOrNull(i)?.takeIf { it != EntryPart.ITEM }
                        val part = if (item != null) EntryPart.ITEM else stored
                        TrackEntry(
                            id = track.id,
                            title = track.title,
                            artists = track.artists,
                            albumId = track.albumId,
                            durationMs = track.durationMs,
                            available = track.available,
                            coverUrl = track.coverUrl,
                            batchId = snapshot.batchIds.getOrElse(i) { "" },
                            part = part?.takeIf { it != defaultPart }?.key,
                            itemId = item?.itemId,
                            addedBy = item?.addedBy,
                            waiting = item?.waiting == true,
                            seedsVersion = snapshot.seedsVersions.getOrNull(i),
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
        val default = EntryPart.defaultFor(file.isWave)
        val parts = file.tracks.map { EntryPart.fromKey(it.part) ?: default }
        val items = file.tracks.map { entry ->
            if (EntryPart.fromKey(entry.part) != EntryPart.ITEM) return@map null
            ItemMark(entry.itemId, entry.addedBy.orEmpty(), entry.waiting)
        }
        val seedsVersions = file.tracks.map(TrackEntry::seedsVersion)
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
            parts = if (file.tracks.all { it.part == null }) emptyList() else parts,
            items = if (items.all { it == null }) emptyList() else items,
            seedsVersions = if (seedsVersions.all { it == null }) emptyList() else seedsVersions,
            continueWithVibe = file.continueWithVibe,
            history = file.history,
        )
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
        val part: String? = null,
        val itemId: String? = null,
        val addedBy: String? = null,
        val waiting: Boolean = false,
        val seedsVersion: Int? = null,
    )
}
