package io.github.kickoman.qiyaa.playback

class CurrentBitrate(private val publish: (Int) -> Unit) {
    private val bitrates =
        object : LinkedHashMap<String, Int>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
                size > CAPACITY
        }
    private var currentTrackId: String? = null

    fun onResolved(trackId: String, kbps: Int) {
        val shown =
            synchronized(this) {
                bitrates[trackId] = kbps
                if (trackId != currentTrackId) return
                kbps
            }
        publish(shown)
    }

    fun onCurrentChanged(trackId: String?) {
        val shown =
            synchronized(this) {
                currentTrackId = trackId
                trackId?.let(bitrates::get) ?: 0
            }
        publish(shown)
    }

    private companion object {
        const val CAPACITY = 64
    }
}
