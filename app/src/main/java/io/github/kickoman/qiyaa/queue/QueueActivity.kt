package io.github.kickoman.qiyaa.queue

sealed interface QueueActivity {
    data object TrackStarted : QueueActivity

    data class PlayingChanged(val playing: Boolean) : QueueActivity

    data class PlaybackFailed(val kind: FailureKind) : QueueActivity
}
