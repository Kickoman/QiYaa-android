package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.EntryPart
import io.github.kickoman.qiyaa.queue.QueueState
import io.github.kickoman.qiyaa.track.TrackSource

val QueueState.fromSpotify: Boolean
    get() = entries.firstOrNull { it.part == EntryPart.HEAD || it.part == EntryPart.CONTINUATION }
        ?.track?.source == TrackSource.SPOTIFY

fun continueNotice(state: QueueState): Int = when {
    !state.continueWithVibe -> R.string.vibe_continue_off
    state.fromSpotify -> R.string.queue_autoplay_on
    else -> R.string.vibe_continue_on
}

fun lockedShuffleNotice(state: QueueState): Int =
    if (state.fromSpotify) R.string.player_shuffle_radio else R.string.player_shuffle_wave

fun lockedRepeatNotice(state: QueueState): Int =
    if (state.fromSpotify) R.string.player_repeat_radio else R.string.player_repeat_wave
