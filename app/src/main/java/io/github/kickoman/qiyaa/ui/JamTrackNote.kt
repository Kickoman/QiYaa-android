package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.EntryPart
import io.github.kickoman.qiyaa.queue.QueueEntry

fun jamTrackNote(entry: QueueEntry, isWave: Boolean, jamOn: Boolean, names: Map<String, String>): TextRes? {
    if (!jamOn) return null
    val item = entry.item
    return when {
        item?.itemId != null -> names[item.addedBy]?.let { TextRes(R.string.jam_added_by, listOf(it)) }
        entry.part == EntryPart.CONTINUATION && !isWave -> TextRes(R.string.jam_wave_mark)
        else -> null
    }
}
