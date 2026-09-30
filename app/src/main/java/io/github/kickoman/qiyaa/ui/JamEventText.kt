package io.github.kickoman.qiyaa.ui

import android.content.Context
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.playback.JamHostEvent

fun JamHostEvent.render(context: Context): String = when (this) {
    is JamHostEvent.Refused -> context.getString(refusedText(reason))
    is JamHostEvent.Ended -> context.getString(
        when (why) {
            JamHostEvent.Ended.Why.BY_HOST -> R.string.jam_ended_by_host
            JamHostEvent.Ended.Why.BY_SERVER -> R.string.jam_ended_by_server
            JamHostEvent.Ended.Why.EXPIRED -> R.string.jam_ended_expired
            JamHostEvent.Ended.Why.GONE -> R.string.jam_ended_gone
        },
    )
}

/** A reason the app does not know is a general failure (protocol, "Reasons"). */
fun refusedText(reason: String): Int = when (reason) {
    "bad-key" -> R.string.jam_refused_bad_key
    "server-full" -> R.string.jam_refused_server_full
    "rate-limited" -> R.string.jam_refused_rate_limited
    "update-required" -> R.string.jam_refused_update_required
    "not-allowed" -> R.string.jam_refused_not_allowed
    "queue-limit" -> R.string.jam_refused_queue_limit
    "duplicate" -> R.string.jam_refused_duplicate
    "stale" -> R.string.jam_refused_stale
    else -> R.string.jam_refused_other
}
