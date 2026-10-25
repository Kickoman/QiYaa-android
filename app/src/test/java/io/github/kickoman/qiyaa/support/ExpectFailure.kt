package io.github.kickoman.qiyaa.support

import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail

suspend inline fun <reified T : Throwable> expectFailure(
    timeoutMs: Long = 10_000,
    crossinline block: suspend () -> Unit,
): T {
    try {
        withTimeout(timeoutMs) { block() }
    } catch (failed: Throwable) {
        if (failed is T) return failed
        throw failed
    }
    fail("expected ${T::class.java.simpleName}")
    throw AssertionError()
}
