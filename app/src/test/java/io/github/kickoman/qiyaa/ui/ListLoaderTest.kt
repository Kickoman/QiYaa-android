package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListLoaderTest {
    @Test
    fun `a list is loading at once and loaded when the reply comes`() = runTest {
        val reply = CompletableDeferred<List<String>>()
        val loader = loader(this) { reply.await() }
        loader.load()
        assertEquals(ListState.Loading, loader.state.value)
        reply.complete(listOf("a"))
        runCurrent()
        assertEquals(ListState.Loaded(listOf("a")), loader.state.value)
    }

    @Test
    fun `a failed list shows the kind of error, logs the failure and loads again when asked`() = runTest {
        var calls = 0
        val logged = ArrayList<Throwable>()
        val loader =
            loader(this, logged) {
                if (++calls ==
                    1
                ) {
                    throw NetworkException("GET", "/users/42/playlists/list", IOException("offline"))
                }
                listOf("a")
            }
        loader.load()
        runCurrent()
        assertEquals(ListState.Failed(ErrorKind.NoNetwork), loader.state.value)
        assertEquals(1, logged.size)
        loader.load()
        runCurrent()
        assertEquals(ListState.Loaded(listOf("a")), loader.state.value)
    }

    @Test
    fun `a loaded list is not fetched again unless forced`() = runTest {
        var calls = 0
        val loader = loader(this) { listOf("v${++calls}") }
        loader.load()
        runCurrent()
        loader.load()
        runCurrent()
        assertEquals(ListState.Loaded(listOf("v1")), loader.state.value)
        loader.load(force = true)
        runCurrent()
        assertEquals(ListState.Loaded(listOf("v2")), loader.state.value)
    }

    @Test
    fun `asking again while the list is loading does not send a second request`() = runTest {
        var calls = 0
        val reply = CompletableDeferred<List<String>>()
        val loader =
            loader(this) {
                calls++
                reply.await()
            }
        loader.load()
        runCurrent()
        loader.load()
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `lists load side by side, and one hanging or failing list does not hold back another`() = runTest {
        val hanging = loader(this) { CompletableDeferred<List<String>>().await() }
        val failing = loader(this) { throw IOException("broken") }
        val working = loader(this) { listOf("a") }
        hanging.load()
        failing.load()
        working.load()
        runCurrent()
        assertEquals(ListState.Loading, hanging.state.value)
        assertEquals(ListState.Failed(ErrorKind.Unknown), failing.state.value)
        assertEquals(ListState.Loaded(listOf("a")), working.state.value)
    }

    @Test
    fun `a forced load drops the reply of the request it replaces`() = runTest {
        val replies = ArrayList<CompletableDeferred<List<String>>>()
        val loader = loader(this) { CompletableDeferred<List<String>>().also(replies::add).await() }
        loader.load()
        runCurrent()
        loader.load(force = true)
        runCurrent()
        replies[1].complete(listOf("new"))
        runCurrent()
        replies[0].complete(listOf("old"))
        runCurrent()
        assertEquals(ListState.Loaded(listOf("new")), loader.state.value)
    }

    @Test
    fun `reset forgets the list and ignores a reply that comes later`() = runTest {
        val reply = CompletableDeferred<List<String>>()
        val loader = loader(this) { reply.await() }
        loader.load()
        runCurrent()
        loader.reset()
        reply.complete(listOf("a"))
        runCurrent()
        assertEquals(ListState.Idle, loader.state.value)
    }

    private fun loader(
        scope: TestScope,
        logged: MutableList<Throwable> = ArrayList(),
        fetch: suspend () -> List<String>,
    ) = ListLoader(scope.backgroundScope, StandardTestDispatcher(scope.testScheduler), fetch, logged::add)
}
