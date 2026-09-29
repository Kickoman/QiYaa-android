package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.QueueSnapshot
import io.github.kickoman.qiyaa.queue.QueueSnapshotCodec
import io.github.kickoman.qiyaa.queue.QueueStore

class FakeQueueStore(var text: String? = null) : QueueStore {
    var writes = 0

    override fun read(): String? = text

    override fun write(text: String) {
        writes++
        this.text = text
    }

    fun snapshot(): QueueSnapshot? = QueueSnapshotCodec.decode(text)
}
