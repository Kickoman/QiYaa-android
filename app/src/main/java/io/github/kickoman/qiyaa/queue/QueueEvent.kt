package io.github.kickoman.qiyaa.queue

sealed interface QueueEvent {
    data class SourceLoading(val title: String) : QueueEvent

    data class SearchStarted(val title: String) : QueueEvent

    data class SourceLoaded(val title: String, val trackCount: Int) : QueueEvent

    data class SourceEmpty(val title: String) : QueueEvent

    data class WaveStarted(val title: String) : QueueEvent

    data object NothingFound : QueueEvent

    data object LoadingMore : QueueEvent

    data class LikeChanged(val liked: Boolean) : QueueEvent

    data object DislikedAndSkipped : QueueEvent

    data object WaitingForNetwork : QueueEvent

    data class StoppedAfterFailures(val count: Int) : QueueEvent

    data class Failed(val stage: Stage, val message: String) : QueueEvent

    enum class Stage { SOURCE, WAVE, WAVE_MORE, SEARCH, LIKE, PLAYBACK }
}
