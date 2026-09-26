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

fun balanceLabel(balance: Int): String = when {
    balance == 0 -> "C"
    balance < 0 -> "L${abs(balance)}"
    else -> "R$balance"
}

fun formatReadout(bitrateKbps: Int, sampleRate: Int, channels: Int): String {
    val bitrate = if (bitrateKbps > 0) "${bitrateKbps}K" else "--K"
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
