package io.github.kickoman.qiyaa.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.jam.JamOrder
import io.github.kickoman.qiyaa.jam.JamSettingsPatch
import io.github.kickoman.qiyaa.playback.JamHost
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.queue.JamSlot
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JamUi(
    val hostName: String = "",
    val searchText: String = "",
    val results: ListState<Track> = ListState.Idle,
)

class JamViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    private val host = graph.jamHost
    private val library = graph.library
    val state = host.state
    val settings = graph.settings

    private val mutableUi = MutableStateFlow(JamUi())
    val ui: StateFlow<JamUi> = mutableUi.asStateFlow()

    private val mutableNotices = MutableSharedFlow<String>(extraBufferCapacity = NOTICE_BUFFER)
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch { host.events.collect { notice(it.render(getApplication())) } }
        viewModelScope.launch {
            library.account.collect { account ->
                val name = account.displayName.ifEmpty { account.login }.trim().take(JamHost.MAX_NAME).trim()
                mutableUi.update { if (it.hostName.isEmpty()) it.copy(hostName = name) else it }
            }
        }
    }

    val isConfigured: Boolean
        get() = settings.jamServer.value.isNotBlank()

    fun setHostName(name: String) = mutableUi.update { it.copy(hostName = name.take(JamHost.MAX_NAME)) }

    fun start() {
        if (!isConfigured) {
            notice(string(R.string.jam_not_configured))
            return
        }
        if (!host.create(mutableUi.value.hostName)) notice(string(R.string.jam_cannot_start))
    }

    fun cancelStart() = host.cancelCreate()

    fun end() = host.end()

    fun continueStored() = host.continueStored()

    fun discardStored() = host.discardStored()

    fun setOrder(order: JamOrder) = changeSettings(JamSettingsPatch(order = order))

    fun setGuestsCanSkip(allowed: Boolean) = changeSettings(JamSettingsPatch(guestsCanSkip = allowed))

    fun setJoinOpen(open: Boolean) = changeSettings(JamSettingsPatch(joinOpen = open))

    fun rotateLink() = sent(host.rotateLink())

    fun kick(publicId: String) = sent(host.kick(publicId))

    fun add(track: Track) = sent(host.add(track), R.string.jam_added)

    fun playNext(track: Track) = sent(host.playNext(track), R.string.jam_added_next)

    /** The playlist's REM during a jam: the selected jam items leave the room's queue. */
    fun removeFromJam(indices: Set<Int>) {
        val slots = graph.queue.state.value.jamSlots ?: return
        val items = indices.mapNotNull { (slots.getOrNull(it) as? JamSlot.Item)?.itemId }
        if (items.isEmpty()) {
            notice(string(R.string.jam_remove_nothing))
            return
        }
        // The items leave the playlist with the next state, and their selection with them.
        if (!items.all(host::remove)) notice(string(R.string.jam_no_connection))
    }

    fun setSearchText(text: String) = mutableUi.update { it.copy(searchText = text) }

    fun search(text: String = mutableUi.value.searchText) {
        val query = text.trim()
        if (query.isEmpty()) return
        mutableUi.update { it.copy(searchText = query, results = ListState.Loading) }
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                val results =
                    try {
                        val tracks = withContext(Dispatchers.IO) { library.searchTracks(query) }
                        ListState.Loaded(tracks.filter { it.available }.take(JamHost.SEARCH_RESULTS))
                    } catch (failed: Exception) {
                        if (failed is CancellationException) throw failed
                        Log.w(LOG_TAG, "Jam search failed", failed)
                        ListState.Failed(ErrorKind.of(failed))
                    }
                mutableUi.update { it.copy(results = results) }
            }
    }

    fun setServer(url: String) = settings.setJamServer(url)

    fun setWaveFeedback(enabled: Boolean) = settings.setJamWaveFeedback(enabled)

    val isActive: Boolean get() = state.value.phase == JamHostPhase.ACTIVE

    private fun changeSettings(patch: JamSettingsPatch) = sent(host.changeSettings(patch))

    private fun sent(accepted: Boolean, doneText: Int? = null) {
        when {
            !accepted -> notice(string(R.string.jam_no_connection))
            doneText != null -> notice(string(doneText))
        }
    }

    fun say(text: String) = notice(text)

    private fun notice(text: String) {
        mutableNotices.tryEmit(text)
    }

    private fun string(id: Int): String = getApplication<Application>().getString(id)

    companion object {
        private const val NOTICE_BUFFER = 8
    }
}
