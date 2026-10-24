package io.github.kickoman.qiyaa.ui

import java.util.Locale
import kotlin.math.abs

fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

fun positionAt(fraction: Float, durationMs: Long): Long? =
    if (durationMs > 0) (durationMs * fraction.coerceIn(0f, 1f)).toLong() else null

fun timeLabel(
    positionMs: Long,
    durationMs: Long,
    hasTrack: Boolean,
    remaining: Boolean,
    dragFraction: Float?,
): String {
    if (!hasTrack) return "00:00"
    val shownMs = dragFraction?.let { positionAt(it, durationMs) } ?: positionMs
    return if (remaining) "-" + formatTime(durationMs - shownMs) else formatTime(shownMs)
}

fun balanceLabel(balance: Int): String = when {
    balance == 0 -> "C"
    balance < 0 -> "L${abs(balance)}"
    else -> "R$balance"
}

fun formatReadout(bitrateKbps: Int, sampleRate: Int, channels: Int): String {
    val bitrate =
        when {
            bitrateKbps > MAX_KBPS_SHOWN -> "${minOf(bitrateKbps / 100, 99)}H"
            bitrateKbps > 0 -> "${bitrateKbps}K"
            else -> "--K"
        }
    val kilohertz = if (sampleRate > 0) "${(sampleRate + 500) / 1000}" else "--"
    val channelMode =
        when {
            channels <= 0 -> "--"
            channels == 1 -> "MO"
            else -> "ST"
        }
    return "$bitrate · $kilohertz · $channelMode"
}

fun formatDb(db: Double): String {
    if (abs(db) < 0.05) return "0"
    val text = "%.1f".format(Locale.ROOT, db).removeSuffix(".0")
    return if (db > 0) "+$text" else text
}

private const val MAX_KBPS_SHOWN = 999
