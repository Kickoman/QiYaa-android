package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track

sealed interface JamSlot {
    data class Item(val itemId: String, val addedBy: String) : JamSlot

    data object Wave : JamSlot

    data object Other : JamSlot
}

data class JamEntry(val itemId: String, val track: Track, val addedBy: String)

data class JamPlayback(
    val kind: Kind,
    val itemId: String?,
    val track: Track?,
    val positionMs: Long,
    val paused: Boolean,
) {
    enum class Kind { ITEM, WAVE, IDLE }
}

interface JamPlaybackListener {
    fun onItemStarted(itemId: String)

    fun onPlayback(playback: JamPlayback)
}

object JamTail {
    data class Edit(val removeFrom: Int, val removeUntil: Int, val insertAt: Int, val insert: List<JamEntry>)

    fun jamPartEnd(slots: List<JamSlot>, currentIndex: Int): Int {
        var end = currentIndex + 1
        while (end < slots.size && slots[end] is JamSlot.Item) end++
        return end
    }

    fun edit(slots: List<JamSlot>, currentIndex: Int, wanted: List<JamEntry>): Edit {
        val start = currentIndex + 1
        val end = jamPartEnd(slots, currentIndex)
        val existing = slots.subList(start, end).map { (it as JamSlot.Item).itemId }
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
