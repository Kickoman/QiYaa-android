package io.github.kickoman.qiyaa.queue

class StartHistory(ids: List<String> = emptyList()) {
    private val latestFirst = ArrayDeque(ids.filter { it.isNotEmpty() }.distinct().take(SEEDS))

    val ids: List<String> get() = latestFirst.toList()

    fun note(trackId: String) {
        if (trackId.isEmpty()) return
        latestFirst.remove(trackId)
        latestFirst.addFirst(trackId)
        while (latestFirst.size > SEEDS) latestFirst.removeLast()
    }

    fun seeds(): List<String> = latestFirst.map { "track:$it" }

    fun clear() = latestFirst.clear()

    companion object {
        const val SEEDS = 5
    }
}
