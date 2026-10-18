package io.github.kickoman.qiyaa.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

@UnstableApi
object PlayerBuffer {
    const val BACK_BUFFER_MS = 10 * 60 * 1_000

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBackBuffer(BACK_BUFFER_MS, false)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
