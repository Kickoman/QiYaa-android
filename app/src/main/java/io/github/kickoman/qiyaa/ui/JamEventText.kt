package io.github.kickoman.qiyaa.ui

import android.content.Context
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.jam.EndReason
import io.github.kickoman.qiyaa.jam.GuestAddResult
import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamGuestEvent
import io.github.kickoman.qiyaa.jam.JamGuestState
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

    JamHostEvent.NotConnected -> context.getString(R.string.jam_no_connection)
}

fun JamGuestEvent.render(context: Context): String = when (this) {
    is JamGuestEvent.Refused -> context.getString(guestRefusedText(reason))
}

/** A reason the app does not know is a general failure (protocol, "Reasons"). */
fun refusedText(reason: String): Int = when (reason) {
    "bad-secret" -> R.string.jam_refused_bad_secret
    "room-not-found" -> R.string.jam_refused_room_not_found
    "room-full" -> R.string.jam_refused_room_full
    "join-closed" -> R.string.jam_refused_join_closed
    "kicked" -> R.string.jam_guest_kicked
    "rate-limited" -> R.string.jam_refused_rate_limited
    "queue-limit" -> R.string.jam_refused_queue_limit
    "duplicate" -> R.string.jam_refused_duplicate
    "not-allowed" -> R.string.jam_refused_not_allowed
    "stale" -> R.string.jam_refused_stale
    "unknown-track" -> R.string.jam_refused_unknown_track
    "track-unavailable" -> R.string.jam_refused_track_unavailable
    "host-offline" -> R.string.jam_refused_host_offline
    "host-timeout" -> R.string.jam_refused_host_timeout
    "host-error" -> R.string.jam_refused_host_error
    "invalid-message" -> R.string.jam_refused_invalid_message
    "update-required" -> R.string.jam_refused_update_required
    "server-full" -> R.string.jam_refused_server_full
    else -> R.string.jam_refused_other
}

fun guestRefusedText(reason: String): Int =
    if (reason == "queue-limit") R.string.jam_refused_guest_queue_limit else refusedText(reason)

fun GuestPhase.endText(): Int? = when (this) {
    GuestPhase.NoSecret -> R.string.jam_guest_no_secret

    is GuestPhase.Refused -> guestRefusedText(reason)

    is GuestPhase.Ended -> when (reason) {
        EndReason.HOST_ENDED -> R.string.jam_guest_ended_host
        EndReason.EXPIRED -> R.string.jam_guest_ended_expired
    }

    GuestPhase.Kicked -> R.string.jam_guest_kicked

    GuestPhase.UpdateRequired -> R.string.jam_refused_update_required

    GuestPhase.None, GuestPhase.NeedName, GuestPhase.Joining -> null

    GuestPhase.InRoom, GuestPhase.WaitingForRoom -> null
}

fun JamGuestState.statusText(): Int? {
    val room = room
    return when {
        phase == GuestPhase.WaitingForRoom -> R.string.jam_guest_waiting_for_room
        phase != GuestPhase.InRoom || room == null -> null
        !connected -> R.string.jam_offline_strip
        !room.hostOnline -> R.string.jam_guest_host_offline
        else -> null
    }
}

fun GuestAddResult.text(): Int? = when (this) {
    GuestAddResult.SENT -> null
    GuestAddResult.NOT_CONNECTED -> R.string.jam_no_connection
    GuestAddResult.HOST_OFFLINE -> R.string.jam_guest_host_offline
    GuestAddResult.INVALID -> R.string.jam_guest_add_invalid
}
