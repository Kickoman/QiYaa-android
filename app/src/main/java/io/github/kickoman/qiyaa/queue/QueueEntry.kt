package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveContext

enum class EntryPart(val key: String) {
    HEAD("head"),
    CONTINUATION("continuation"),
    ;

    companion object {
        fun fromKey(key: String?): EntryPart? = entries.firstOrNull { it.key == key }

        fun defaultFor(isWave: Boolean): EntryPart = if (isWave) CONTINUATION else HEAD
    }
}

data class QueueEntry(val track: Track, val part: EntryPart = EntryPart.HEAD, val wave: WaveContext? = null)
