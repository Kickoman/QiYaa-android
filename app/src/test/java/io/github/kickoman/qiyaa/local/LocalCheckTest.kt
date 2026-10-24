package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.support.localRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCheckTest {
    private val first = localRow(1, "Track 1", "Abbey", 7, 1, "Music", file = "1.flac")
    private val second = localRow(2, "Track 2", "Abbey", 7, 2, "Music", file = "2.flac")
    private val saved = listOf(LocalTracks.toTrack(first), LocalTracks.toTrack(second))

    @Test
    fun `LOCAL-14 the same titles and lengths mean the same library`() {
        assertEquals(listOf(1L, 2L), LocalCheck.localIds(saved))
        assertFalse(LocalCheck.renumbered(saved, mapOf(1L to first, 2L to second)))
    }

    @Test
    fun `LOCAL-14 a file that is gone is not a renumbered library`() {
        assertFalse(LocalCheck.renumbered(saved, mapOf(1L to first)))
    }

    @Test
    fun `LOCAL-14 another title for a saved id means a renumbered library`() {
        assertTrue(LocalCheck.renumbered(saved, mapOf(1L to first, 2L to second.copy(title = "Voice memo"))))
    }

    @Test
    fun `LOCAL-14 another length for a saved id means a renumbered library`() {
        assertTrue(LocalCheck.renumbered(saved, mapOf(1L to first.copy(durationMs = 99_000), 2L to second)))
    }
}
