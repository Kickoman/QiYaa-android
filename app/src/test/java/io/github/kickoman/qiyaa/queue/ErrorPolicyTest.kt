package io.github.kickoman.qiyaa.queue

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ErrorPolicyTest {
    @Test
    fun `a network failure waits for the network instead of skipping`() {
        assertEquals(ErrorAction.WaitForNetwork, ErrorPolicy.decide(FailureKind.NETWORK, 0, hasNext = true))
        assertEquals(ErrorAction.WaitForNetwork, ErrorPolicy.decide(FailureKind.NETWORK, 2, hasNext = true))
    }

    @Test
    fun `a session failure holds the queue in place`() {
        assertEquals(ErrorAction.Hold, ErrorPolicy.decide(FailureKind.SESSION, 0, hasNext = true))
    }

    @Test
    fun `a broken track is skipped twice in a row and the third failure stops`() {
        assertEquals(ErrorAction.SkipToNext, ErrorPolicy.decide(FailureKind.TRACK, 0, hasNext = true))
        assertEquals(ErrorAction.SkipToNext, ErrorPolicy.decide(FailureKind.TRACK, 1, hasNext = true))
        assertEquals(ErrorAction.Stop, ErrorPolicy.decide(FailureKind.TRACK, 2, hasNext = true))
    }

    @Test
    fun `a broken last track stops`() {
        assertEquals(ErrorAction.Stop, ErrorPolicy.decide(FailureKind.TRACK, 0, hasNext = false))
    }

    @Test
    fun `awaitRetry returns as soon as a missing network comes back`() = runTest {
        val network = MutableStateFlow(false)
        val retry = async { ErrorPolicy.awaitRetry(network, attempt = 3) }
        advanceTimeBy(5 * 60_000L)
        assertFalse(retry.isCompleted)
        network.value = true
        runCurrent()
        assertTrue(retry.isCompleted)
        assertEquals(5 * 60_000L, currentTime)
    }

    @Test
    fun `awaitRetry backs off from 2 to 60 seconds while the network is up`() = runTest {
        val network = MutableStateFlow(true)
        val delays =
            listOf(0, 1, 2, 3, 4, 5, 6, 30).map { attempt ->
                val start = currentTime
                ErrorPolicy.awaitRetry(network, attempt)
                (currentTime - start) / 1000
            }
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L), delays)
    }
}
