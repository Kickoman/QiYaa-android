package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.EntryPart
import io.github.kickoman.qiyaa.queue.ItemMark
import io.github.kickoman.qiyaa.queue.QueueEntry
import io.github.kickoman.qiyaa.queue.QueueState
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueNoticesTest {
    @Test
    fun `SPOT-20 a Spotify queue with the flag on says Spotify's autoplay follows, others a vibe`() {
        assertEquals(R.string.queue_autoplay_on, continueNotice(queue(head(SPOTIFY), on = true)))
        assertEquals(R.string.vibe_continue_on, continueNotice(queue(head(YANDEX), on = true)))
        assertEquals(R.string.vibe_continue_on, continueNotice(queue(head(LOCAL), on = true)))
        assertEquals(R.string.vibe_continue_off, continueNotice(queue(head(SPOTIFY), on = false)))
        assertEquals(R.string.vibe_continue_off, continueNotice(queue(head(YANDEX), on = false)))
    }

    @Test
    fun `SPOT-12 a Spotify radio's shuffle and repeat speak of the radio, a Yandex vibe's of the vibe`() {
        val radio = queue(QueueEntry(SPOTIFY, EntryPart.CONTINUATION), wave = true)
        val vibe = queue(QueueEntry(YANDEX, EntryPart.CONTINUATION), wave = true)
        assertEquals(R.string.player_shuffle_radio, lockedShuffleNotice(radio))
        assertEquals(R.string.player_repeat_radio, lockedRepeatNotice(radio))
        assertEquals(R.string.player_shuffle_wave, lockedShuffleNotice(vibe))
        assertEquals(R.string.player_repeat_wave, lockedRepeatNotice(vibe))
    }

    @Test
    fun `in a jam the base's tracks decide, not a guest's track ahead of them`() {
        val guest = QueueEntry(YANDEX, EntryPart.ITEM, item = ItemMark("i1", "guest", waiting = true))
        val played = QueueEntry(YANDEX, EntryPart.OTHER)
        assertTrue(queue(guest, played, head(SPOTIFY)).fromSpotify)
        assertFalse(queue(guest, head(YANDEX), head(SPOTIFY)).fromSpotify)
        assertFalse(queue(guest).fromSpotify)
        assertFalse(QueueState().fromSpotify)
    }

    private fun head(track: Track) = QueueEntry(track, EntryPart.HEAD)

    private fun queue(vararg entries: QueueEntry, on: Boolean = false, wave: Boolean = false) =
        QueueState(entries = entries.toList(), isWave = wave, continueWithVibe = on)

    private companion object {
        val SPOTIFY = Track("6PCoZdjSPbRMehHviTrpB3", "Кукушка", source = TrackSource.SPOTIFY)
        val YANDEX = Track("42", "Группа крови")
        val LOCAL = Track("7", "Свой", source = TrackSource.LOCAL)
    }
}
