package io.github.kickoman.qiyaa.spotify.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMessagesTest {
    @Test
    fun `a list has more only while the offset asked plus the page is below its length`() {
        assertFalse(ListPage(length = 1000).hasMoreAfter(0, 1000))
        assertTrue(ListPage(length = 1001).hasMoreAfter(0, 1000))
        assertFalse(ListPage(length = 2000).hasMoreAfter(1000, 1000))
        assertTrue(ListPage(length = 2001).hasMoreAfter(1000, 1000))
    }

    @Test
    fun `the offset plus the page does not overflow near the largest length`() {
        assertFalse(ListPage(length = Int.MAX_VALUE).hasMoreAfter(Int.MAX_VALUE - 10, 1000))
        assertTrue(ListPage(length = Int.MAX_VALUE).hasMoreAfter(Int.MAX_VALUE - 1001, 1000))
    }
}
