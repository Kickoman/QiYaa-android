package io.github.kickoman.qiyaa.ui

import kotlin.math.abs

/** mm:ss, or h:mm:ss above an hour (as the mock's fmt()). */
fun fmtTime(ms: Long): String {
    val sec = (ms.coerceAtLeast(0) + 500) / 1000
    val h = sec / 3600
    val m = (sec / 60) % 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Balance readout: C, L25, R100. */
fun balanceLabel(balance: Int): String = if (balance == 0) "C" else (if (balance < 0) "L" else "R") + abs(balance)

/** "320K · 44 · ST" readout; dashes while unknown. */
fun formatReadout(bitrateKbps: Int, sampleRate: Int, channels: Int): String {
    val k = if (bitrateKbps > 0) "${bitrateKbps}K" else "--K"
    val hz = if (sampleRate > 0) "${(sampleRate + 500) / 1000}" else "--"
    val ch = when {
        channels <= 0 -> "--"
        channels == 1 -> "MO"
        else -> "ST"
    }
    return "$k · $hz · $ch"
}

/** EQ value label: "0", "+3.5", "-12". */
fun formatDb(db: Double): String {
    if (abs(db) < 0.05) return "0"
    val s = "%.1f".format(java.util.Locale.ROOT, db).removeSuffix(".0")
    return if (db > 0) "+$s" else s
}
