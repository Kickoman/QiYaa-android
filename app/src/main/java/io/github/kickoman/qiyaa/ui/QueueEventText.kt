package io.github.kickoman.qiyaa.ui

import android.content.Context
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.QueueEvent
import io.github.kickoman.qiyaa.queue.QueueEvent.Stage

fun QueueEvent.render(context: Context): String = when (this) {
    is QueueEvent.SourceLoading -> context.getString(R.string.queue_loading, title)
    is QueueEvent.SearchStarted -> context.getString(R.string.queue_searching, title)
    is QueueEvent.SourceLoaded ->
        context.resources.getQuantityString(R.plurals.queue_loaded, trackCount, title, trackCount)
    is QueueEvent.SourceEmpty -> context.getString(R.string.queue_empty, title)
    is QueueEvent.WaveStarted -> context.getString(R.string.queue_wave_started, title)
    QueueEvent.NothingFound -> context.getString(R.string.queue_nothing_found)
    QueueEvent.LoadingMore -> context.getString(R.string.playlist_loading_more_toast)
    is QueueEvent.LikeChanged -> context.getString(
        if (liked) R.string.queue_liked else R.string.queue_unliked,
    )
    QueueEvent.DislikedAndSkipped -> context.getString(R.string.queue_disliked)
    QueueEvent.WaitingForNetwork -> context.getString(R.string.queue_waiting_for_network)
    is QueueEvent.StoppedAfterFailures ->
        context.resources.getQuantityString(R.plurals.queue_stopped_after_failures, count, count)
    is QueueEvent.Failed -> context.getString(stage.stringId(), error.render(context))
}

private fun Stage.stringId(): Int = when (this) {
    Stage.SOURCE, Stage.LIKE -> R.string.error_generic
    Stage.WAVE -> R.string.queue_wave_error
    Stage.WAVE_MORE -> R.string.queue_wave_more_error
    Stage.SEARCH -> R.string.queue_search_error
    Stage.PLAYBACK -> R.string.queue_playback_error
}
