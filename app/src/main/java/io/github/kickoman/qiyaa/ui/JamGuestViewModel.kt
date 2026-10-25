package io.github.kickoman.qiyaa.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.jam.GuestAddResult
import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamGuestState
import io.github.kickoman.qiyaa.jam.JamNames
import io.github.kickoman.qiyaa.playback.JamTracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.Account
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.PlaylistRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GuestTab { SEARCH, LIKED, PLAYLISTS }

data class JamGuestUi(
    val name: String = "",
    val tab: GuestTab = GuestTab.SEARCH,
    val searchText: String = "",
    val results: ListState<Track> = ListState.Idle,
    val likedFilter: String = "",
    val playlist: PlaylistRef? = null,
    val playlistTracks: ListState<Track> = ListState.Idle,
)

class JamGuestViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    private val guest = graph.jamGuest
    private val library = graph.library
    val state: StateFlow<JamGuestState> = guest.state
    val likedIds: StateFlow<Set<String>> = library.likedIds

    private val mutableUi = MutableStateFlow(JamGuestUi())
    val ui: StateFlow<JamGuestUi> = mutableUi.asStateFlow()

    private val mutableNotices = MutableSharedFlow<String>(extraBufferCapacity = NOTICE_BUFFER)
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()

    val playlists = listLoader("Guest playlists") { library.userPlaylists() }
    private val liked = listLoader("Guest likes") { library.likedTracks().addable() }
    val likedShown: StateFlow<ListState<Track>> =
        combine(liked.state, mutableUi.map { it.likedFilter.trim() }.distinctUntilChanged()) { list, filter ->
            if (list is ListState.Loaded && filter.isNotEmpty()) {
                ListState.Loaded(list.items.filter { it.matches(filter) })
            } else {
                list
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ListState.Idle)

    private var nameEdited = false
    private var searchJob: Job? = null
    private var playlistJob: Job? = null
    private var likeJob: Job? = null

    init {
        viewModelScope.launch { guest.events.collect { notice(it.render(getApplication())) } }
        viewModelScope.launch {
            combine(guest.state, library.account) { state, account ->
                state to account
            }.collect { (state, account) ->
                if (state.phase != GuestPhase.NeedName) {
                    nameEdited = false
                } else if (!nameEdited) {
                    mutableUi.update { it.copy(name = prefilledName(state.lastName, account)) }
                }
            }
        }
        viewModelScope.launch {
            library.account.collect { account -> if (!account.isValid) forgetLists() }
        }
    }

    fun serverNow(): Long = guest.serverNow()

    fun setName(text: String) {
        nameEdited = true
        mutableUi.update { it.copy(name = text) }
    }

    fun join() {
        if (!guest.join(mutableUi.value.name)) notice(string(R.string.jam_guest_name_invalid))
    }

    fun leave() = guest.leave()

    fun add(track: Track) {
        val result = JamTracks.toJam(track)?.let(guest::add) ?: GuestAddResult.INVALID
        result.text()?.let { notice(string(it)) }
    }

    fun remove(itemId: String) {
        if (!guest.remove(itemId)) notice(string(R.string.jam_no_connection))
    }

    fun skip() {
        val itemId = state.value.room?.nowPlaying?.itemId
        if (itemId == null || !guest.skip(itemId)) notice(string(R.string.jam_no_connection))
    }

    fun toggleLike() {
        val track = state.value.room?.nowPlaying?.track?.takeIf { it.isCatalog } ?: return
        if (likeJob?.isActive == true) return
        val liked = !library.isLiked(track.id)
        likeJob =
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { library.setLiked(track.id, liked) }
                    notice(string(if (liked) R.string.queue_liked else R.string.queue_unliked))
                } catch (failed: Exception) {
                    if (failed is CancellationException) throw failed
                    Log.w(LOG_TAG, "Guest like failed", failed)
                    notice(string(R.string.error_generic, ErrorKind.of(failed).render(getApplication())))
                }
            }
    }

    fun setTab(tab: GuestTab) {
        mutableUi.update { it.copy(tab = tab) }
        when (tab) {
            GuestTab.SEARCH -> Unit
            GuestTab.LIKED -> liked.load()
            GuestTab.PLAYLISTS -> playlists.load()
        }
    }

    fun retry() {
        when (mutableUi.value.tab) {
            GuestTab.SEARCH -> search()
            GuestTab.LIKED -> liked.load()
            GuestTab.PLAYLISTS -> mutableUi.value.playlist?.let(::openPlaylist) ?: playlists.load()
        }
    }

    fun setSearchText(text: String) = mutableUi.update { it.copy(searchText = text) }

    fun search() {
        val query = mutableUi.value.searchText.trim()
        if (query.isEmpty()) return
        mutableUi.update { it.copy(searchText = query, results = ListState.Loading) }
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                val results = fetch("Guest search") { library.searchTracks(query).addable() }
                mutableUi.update { it.copy(results = results) }
            }
    }

    fun setLikedFilter(text: String) = mutableUi.update { it.copy(likedFilter = text) }

    fun openPlaylist(playlist: PlaylistRef) {
        mutableUi.update { it.copy(playlist = playlist, playlistTracks = ListState.Loading) }
        playlistJob?.cancel()
        playlistJob =
            viewModelScope.launch {
                val tracks = fetch("Guest playlist") { library.playlistTracks(playlist).addable() }
                mutableUi.update { if (it.playlist == playlist) it.copy(playlistTracks = tracks) else it }
            }
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        mutableUi.update { it.copy(playlist = null, playlistTracks = ListState.Idle) }
    }

    private fun forgetLists() {
        searchJob?.cancel()
        playlistJob?.cancel()
        liked.reset()
        playlists.reset()
        mutableUi.update { JamGuestUi(name = it.name) }
    }

    private suspend fun fetch(what: String, load: suspend () -> List<Track>): ListState<Track> = try {
        ListState.Loaded(withContext(Dispatchers.IO) { load() })
    } catch (failed: Exception) {
        if (failed is CancellationException) throw failed
        Log.w(LOG_TAG, "$what failed", failed)
        ListState.Failed(ErrorKind.of(failed))
    }

    private fun <T> listLoader(what: String, fetch: suspend () -> List<T>) = ListLoader(
        viewModelScope,
        Dispatchers.IO,
        fetch,
        logFailure = { failed -> Log.w(LOG_TAG, "$what failed", failed) },
    )

    private fun notice(text: String) {
        mutableNotices.tryEmit(text)
    }

    private fun string(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    companion object {
        private const val NOTICE_BUFFER = 8

        private fun prefilledName(lastName: String?, account: Account): String =
            JamNames.prefill(lastName ?: account.displayName.ifEmpty { account.login })

        private fun List<Track>.addable(): List<Track> = filter {
            it.available && JamTracks.toJam(it) != null
        }

        private fun Track.matches(filter: String): Boolean = title.contains(filter, ignoreCase = true) ||
            artists.any { it.contains(filter, ignoreCase = true) }
    }
}
