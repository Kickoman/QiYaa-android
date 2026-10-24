package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.WaveContext

enum class EntryPart(val key: String) {
    HEAD("head"),
    CONTINUATION("continuation"),
    ITEM("item"),
    OTHER("other"),
    ;

    companion object {
        fun fromKey(key: String?): EntryPart? = entries.firstOrNull { it.key == key }

        fun defaultFor(isWave: Boolean): EntryPart = if (isWave) CONTINUATION else HEAD
    }
}

data class ItemMark(val itemId: String?, val addedBy: String, val waiting: Boolean)

data class QueueEntry(
    val track: Track,
    val part: EntryPart = EntryPart.HEAD,
    val wave: WaveContext? = null,
    val item: ItemMark? = null,
    val seedsVersion: Int? = null,
) {
    init {
        require((part == EntryPart.ITEM) == (item != null)) {
            "An entry of part $part has the item mark $item"
        }
    }

    val isWaiting: Boolean get() = item?.waiting == true

    val isJamItem: Boolean get() = isWaiting && item?.itemId != null

    val isSpent: Boolean get() = part == EntryPart.OTHER || item?.waiting == false
}
