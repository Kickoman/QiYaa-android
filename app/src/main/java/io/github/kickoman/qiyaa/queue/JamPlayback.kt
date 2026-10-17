package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track

data class JamEntry(val itemId: String, val track: Track, val addedBy: String)

data class SeedHint(val seeds: List<String>, val version: Int)

data class JamPlayback(
    val kind: Kind,
    val itemId: String?,
    val track: Track?,
    val positionMs: Long,
    val paused: Boolean,
    /** The track that plays after this one, if any: its file may be shared too (LISTEN-03). */
    val nextTrack: Track? = null,
) {
    enum class Kind { ITEM, BASE, IDLE }
}

interface JamPlaybackListener {
    fun onItemStarted(itemId: String)

    fun onPlayback(playback: JamPlayback)

    fun onRemoveItems(itemIds: List<String>)
}

object JamTail {
    data class Edit(val removeFrom: Int, val removeUntil: Int, val insertAt: Int, val insert: List<JamEntry>)

    fun jamPartEnd(entries: List<QueueEntry>, currentIndex: Int): Int {
        var end = currentIndex + 1
        while (end < entries.size && entries[end].isJamItem) end++
        return end
    }

    fun edit(entries: List<QueueEntry>, currentIndex: Int, wanted: List<JamEntry>): Edit {
        val start = currentIndex + 1
        val end = jamPartEnd(entries, currentIndex)
        val existing = entries.subList(start, end).map { it.item?.itemId }
        var prefix = 0
        while (prefix < existing.size &&
            prefix < wanted.size &&
            existing[prefix] == wanted[prefix].itemId
        ) {
            prefix++
        }
        return Edit(
            removeFrom = start + prefix,
            removeUntil = end,
            insertAt = start + prefix,
            insert = wanted.drop(prefix),
        )
    }
}
