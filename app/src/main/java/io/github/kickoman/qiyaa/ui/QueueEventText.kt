package io.github.kickoman.qiyaa.ui

import android.content.Context
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.playback.QueueEvent
import io.github.kickoman.qiyaa.playback.QueueEvent.Stage

fun QueueEvent.render(context: Context): String = when (this) {
    is QueueEvent.SourceLoading -> context.getString(R.string.queue_loading, title)
    is QueueEvent.SearchStarted -> context.getString(R.string.queue_searching, title)
    is QueueEvent.SourceLoaded ->
        context.resources.getQuantityString(R.plurals.queue_loaded, trackCount, title, trackCount)
    is QueueEvent.SourceEmpty -> context.getString(R.string.queue_empty, title)
    is QueueEvent.WaveStarted -> context.getString(R.string.queue_wave_started, title)
    QueueEvent.NothingFound -> context.getString(R.string.queue_nothing_found)
    QueueEvent.PlayerNotReady -> context.getString(R.string.queue_player_not_ready)
    is QueueEvent.LikeChanged -> context.getString(
        if (liked) R.string.queue_liked else R.string.queue_unliked,
    )
    QueueEvent.DislikedAndSkipped -> context.getString(R.string.queue_disliked)
    is QueueEvent.Failed -> context.getString(stage.stringId(), message)
}

private fun Stage.stringId(): Int = when (this) {
    Stage.SOURCE, Stage.LIKE -> R.string.error_generic
    Stage.WAVE -> R.string.queue_wave_error
    Stage.WAVE_MORE -> R.string.queue_wave_more_error
    Stage.SEARCH -> R.string.queue_search_error
    Stage.PLAYBACK -> R.string.queue_playback_error
}
