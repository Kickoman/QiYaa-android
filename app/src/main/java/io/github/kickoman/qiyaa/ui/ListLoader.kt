package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.yandex.ErrorKind
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ListState<out T> {
    data object Idle : ListState<Nothing>

    data object Loading : ListState<Nothing>

    data class Loaded<T>(val items: List<T>) : ListState<T>

    data class Failed(val error: ErrorKind) : ListState<Nothing>
}

fun <T> ListState<T>.itemsOrEmpty(): List<T> = (this as? ListState.Loaded)?.items.orEmpty()

class ListLoader<T>(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val fetch: suspend () -> List<T>,
    private val logFailure: (Throwable) -> Unit,
) {
    private val mutableState = MutableStateFlow<ListState<T>>(ListState.Idle)
    val state: StateFlow<ListState<T>> = mutableState.asStateFlow()
    private var job: Job? = null

    fun load(force: Boolean = false) {
        val current = mutableState.value
        if (!force && (current == ListState.Loading || current is ListState.Loaded)) return
        job?.cancel()
        mutableState.value = ListState.Loading
        job =
            scope.launch {
                mutableState.value =
                    try {
                        ListState.Loaded(withContext(io) { fetch() })
                    } catch (failed: CancellationException) {
                        throw failed
                    } catch (failed: Exception) {
                        logFailure(failed)
                        ListState.Failed(ErrorKind.of(failed))
                    }
            }
    }

    fun reset() {
        job?.cancel()
        job = null
        mutableState.value = ListState.Idle
    }
}
