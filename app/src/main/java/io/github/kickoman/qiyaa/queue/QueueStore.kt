package io.github.kickoman.qiyaa.queue

interface QueueStore {
    fun read(): String?

    fun write(text: String)
}
