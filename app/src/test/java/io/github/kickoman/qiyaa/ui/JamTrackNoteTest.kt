package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.EntryPart
import io.github.kickoman.qiyaa.queue.ItemMark
import io.github.kickoman.qiyaa.queue.QueueEntry
import io.github.kickoman.qiyaa.yandex.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamTrackNoteTest {
    private val names = mapOf("g1" to "Маша", "g2" to "Алесь")
    private val track = Track("1", "Кукушка")
    private val waiting = QueueEntry(track, EntryPart.ITEM, item = ItemMark("i1", "g1", waiting = true))
    private val played = QueueEntry(track, EntryPart.ITEM, item = ItemMark("i2", "g2", waiting = false))
    private val vibe = QueueEntry(track, EntryPart.CONTINUATION, seedsVersion = 3)

    @Test
    fun `HOST-34 a jam item carries who added it, a jam vibe track its mark, a base track nothing`() {
        assertEquals(TextRes(R.string.jam_added_by, listOf("Маша")), note(waiting))
        assertEquals(TextRes(R.string.jam_added_by, listOf("Алесь")), note(played))
        assertEquals(TextRes(R.string.jam_wave_mark), note(vibe))
        assertEquals(TextRes(R.string.jam_wave_mark), note(QueueEntry(track, EntryPart.CONTINUATION)))
        assertNull(note(QueueEntry(track, EntryPart.HEAD)))
        assertNull(note(QueueEntry(track, EntryPart.OTHER)))
    }

    @Test
    fun `HOST-34 nothing outside a jam, for an unknown guest or for an earlier jam's item`() {
        for (entry in listOf(
            waiting,
            played,
            vibe,
        )) {
            assertNull(jamTrackNote(entry, false, jamOn = false, names))
        }
        assertNull(note(QueueEntry(track, EntryPart.ITEM, item = ItemMark("i3", "g9", waiting = true))))
        assertNull(note(QueueEntry(track, EntryPart.ITEM, item = ItemMark(null, "g1", waiting = true))))
    }

    @Test
    fun `HOST-34 D14 the continuation of a base with a wave of its own is not the jam vibe`() {
        assertNull(
            jamTrackNote(QueueEntry(track, EntryPart.CONTINUATION), isWave = true, jamOn = true, names),
        )
        assertEquals(
            TextRes(R.string.jam_added_by, listOf("Маша")),
            jamTrackNote(waiting, isWave = true, jamOn = true, names),
        )
    }

    private fun note(entry: QueueEntry): TextRes? = jamTrackNote(entry, isWave = false, jamOn = true, names)
}
