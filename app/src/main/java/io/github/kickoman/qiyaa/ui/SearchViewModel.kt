package io.github.kickoman.qiyaa.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kickoman.qiyaa.AppGraph
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.errorKindOf
import io.github.kickoman.qiyaa.playback.JamHostEvent
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.playback.JamHostState
import io.github.kickoman.qiyaa.playback.JamTracks
import io.github.kickoman.qiyaa.queue.AddPlacement
import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.spotify.SpotifyTracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.signedIn
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface SearchGateway {
    fun canSearch(source: SearchSource): Boolean

    suspend fun find(source: SearchSource, text: String): Found

    suspend fun tracksOf(source: SearchSource, pick: FoundPick): List<Track>

    fun autoplay(contextUri: String): Continuation

    val jam: StateFlow<JamHostState>

    val jamEvents: Flow<JamHostEvent>

    fun jamAdd(track: Track): Boolean

    fun jamPlayNext(track: Track): Boolean

    fun jamCanAdd(track: Track): Boolean
}

class SearchViewModel(
    private val gateway: SearchGateway,
    private val queue: QueueController,
    private val strings: (Int, List<Any>) -> String,
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) : ViewModel() {
    private val mutableUi = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = mutableUi.asStateFlow()

    private val mutableNotices = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = mutableNotices.receiveAsFlow()

    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var searches = 0
    private var opens = 0

    init {
        viewModelScope.launch { gateway.jam.collect(::onJam) }
        viewModelScope.launch {
            gateway.jamEvents.collect { event ->
                if (event !is JamHostEvent.AddRefused) return@collect
                mutableUi.update {
                    it.copy(
                        sending = it.sending - event.trackId,
                        refused =
                            it.refused + (event.trackId to event.reason),
                    )
                }
            }
        }
    }

    private fun onJam(state: JamHostState) {
        val room = state.room
        if (state.phase != JamHostPhase.ACTIVE) {
            mutableUi.update { it.copy(jam = null, sending = emptySet(), refused = emptyMap()) }
            return
        }
        val names = room?.participants.orEmpty().associate { it.publicId to it.name }
        val waiting = LinkedHashMap<String, String>()
        for (item in room?.queue.orEmpty()) waiting.putIfAbsent(item.track.id, names[item.addedBy].orEmpty())
        mutableUi.update {
            it.copy(
                jam = SearchJam(state.connected, waiting),
                sending = if (state.connected) it.sending - waiting.keys else emptySet(),
            )
        }
    }

    fun jamAddable(track: Track): Boolean = gateway.jamCanAdd(track)

    fun canSearch(source: SearchSource): Boolean = gateway.canSearch(source)

    fun open(text: String, source: SearchSource) {
        mutableUi.update { it.copy(source = source, text = text) }
        search(text)
    }

    fun setText(text: String) = mutableUi.update { it.copy(text = text) }

    fun setSource(source: SearchSource) {
        val ui = mutableUi.value
        if (ui.source == source) return
        mutableUi.update { it.copy(source = source) }
        if (ui.query.isEmpty()) reset() else search(ui.query)
    }

    fun search() = search(mutableUi.value.text)

    fun retry() = search(mutableUi.value.query)

    private fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        val source = mutableUi.value.source
        val request = ++searches
        dropOpened()
        searchJob?.cancel()
        val searching = gateway.canSearch(source)
        mutableUi.update {
            it.copy(
                text = query,
                query = query,
                phase = if (searching) SearchPhase.Searching else SearchPhase.Idle,
                expanded = emptySet(),
                added = emptyMap(),
                adding = null,
                sending = emptySet(),
                refused = emptyMap(),
            )
        }
        if (!searching) return
        searchJob =
            viewModelScope.launch {
                val phase =
                    try {
                        val found = withContext(io) { gateway.find(source, query) }
                        if (found.isEmpty) SearchPhase.NothingFound else SearchPhase.Results(found)
                    } catch (failed: CancellationException) {
                        throw failed
                    } catch (failed: Exception) {
                        log("Search failed", failed)
                        SearchPhase.Failed(errorKindOf(failed))
                    }
                if (request == searches) mutableUi.update { it.copy(phase = phase) }
            }
    }

    private fun reset() {
        searches++
        searchJob?.cancel()
        dropOpened()
        mutableUi.update { it.copy(phase = SearchPhase.Idle, expanded = emptySet(), added = emptyMap()) }
    }

    fun toggle(part: SearchPart) = mutableUi.update {
        it.copy(expanded = if (part in it.expanded) it.expanded - part else it.expanded + part)
    }

    fun openPick(pick: FoundPick) {
        val source = mutableUi.value.source
        val request = ++opens
        openJob?.cancel()
        mutableUi.update { it.copy(opened = SearchOpened(pick), adding = null) }
        openJob =
            viewModelScope.launch {
                val tracks =
                    try {
                        val all = withContext(io) { gateway.tracksOf(source, pick) }
                        ListState.Loaded(all.filter { it.available })
                    } catch (failed: CancellationException) {
                        throw failed
                    } catch (failed: Exception) {
                        log("Search: opening ${pick.kind} failed", failed)
                        ListState.Failed(errorKindOf(failed))
                    }
                if (request == opens) mutableUi.update { it.copy(opened = it.opened?.copy(tracks = tracks)) }
            }
    }

    fun reopen() {
        mutableUi.value.opened?.let { openPick(it.pick) }
    }

    fun back(): Boolean {
        if (mutableUi.value.opened == null) return false
        dropOpened()
        return true
    }

    private fun dropOpened() {
        opens++
        openJob?.cancel()
        mutableUi.update { it.copy(opened = null) }
    }

    fun play(track: Track) {
        val ui = mutableUi.value
        val opened = ui.opened
        if (opened != null) {
            val tracks = opened.tracks.itemsOrEmpty()
            playOpened(opened.pick, tracks, tracks.indexOfFirst { it.key == track.key }.coerceAtLeast(0))
            return
        }
        val found = (ui.phase as? SearchPhase.Results)?.found ?: return
        val tracks = found.playable()
        val start = tracks.indexOfFirst { it.key == track.key }.coerceAtLeast(0)
        val continuation = tracks.firstOrNull()?.takeIf { ui.source == SearchSource.SPOTIFY }?.let {
            gateway.autoplay(SpotifyTracks.uriOf(it))
        }
        queue.playTracks(searchTitle(ui), tracks, start, continuation = continuation)
    }

    fun playAll() {
        val opened = mutableUi.value.opened ?: return
        playOpened(opened.pick, opened.tracks.itemsOrEmpty(), 0)
    }

    private fun playOpened(pick: FoundPick, tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        queue.playTracks(pick.name, tracks, start, sourceIdOf(pick), continuationOf(pick))
    }

    fun playPick(pick: FoundPick) {
        val source = mutableUi.value.source
        queue.loadSource(pick.name, sourceId = sourceIdOf(pick), continuation = continuationOf(pick)) {
            gateway.tracksOf(source, pick)
        }
    }

    fun chooseAdd(target: SearchTarget) = mutableUi.update { it.copy(adding = target) }

    fun closeAdd() = mutableUi.update { it.copy(adding = null) }

    fun add(placement: AddPlacement) {
        val ui = mutableUi.value
        val target = ui.adding ?: return
        mutableUi.update { it.copy(adding = null) }
        val jam = ui.jam
        if (jam != null) {
            if (target is SearchTarget.OfTrack) addToJam(target.track, placement, jam)
            return
        }
        when (target) {
            is SearchTarget.OfTrack -> {
                queue.add(listOf(target.track), ui.opened?.pick?.name ?: searchTitle(ui), placement)
                mutableUi.update { it.copy(added = it.added + (target.track.key to placement)) }
            }

            is SearchTarget.OfAlbum -> addAlbum(ui, target.album, placement)
        }
    }

    private fun addToJam(track: Track, placement: AddPlacement, jam: SearchJam) {
        val waiting = track.isYandex && track.id in jam.waiting
        val sent = if (placement == AddPlacement.NEXT) gateway.jamPlayNext(track) else gateway.jamAdd(track)
        if (!sent) {
            notice(strings(R.string.jam_no_connection, emptyList()))
            return
        }
        if (!waiting) {
            mutableUi.update {
                it.copy(
                    sending = it.sending + track.id,
                    refused =
                        it.refused - track.id,
                )
            }
        }
        notice(
            strings(
                if (placement ==
                    AddPlacement.NEXT
                ) {
                    R.string.jam_added_next
                } else {
                    R.string.jam_added
                },
                emptyList(),
            ),
        )
    }

    private fun notice(text: String) {
        mutableNotices.trySend(text)
    }

    private fun addAlbum(ui: SearchUi, album: FoundPick, placement: AddPlacement) {
        val opened = ui.opened?.takeIf { it.pick == album }?.tracks as? ListState.Loaded
        if (opened != null) {
            queue.add(opened.items, album.name, placement)
            return
        }
        val source = ui.source
        queue.addLoaded(album.name, placement) { gateway.tracksOf(source, album) }
    }

    private fun searchTitle(ui: SearchUi): String = strings(R.string.queue_search_title, listOf(ui.query))

    private fun sourceIdOf(pick: FoundPick): String = when (mutableUi.value.source) {
        SearchSource.SPOTIFY -> pick.id

        SearchSource.YANDEX -> when (pick.kind) {
            FoundPick.Kind.ARTIST -> "artist:${pick.id}"
            FoundPick.Kind.ALBUM -> "album:${pick.id}"
        }
    }

    private fun continuationOf(pick: FoundPick): Continuation? =
        if (mutableUi.value.source == SearchSource.SPOTIFY) gateway.autoplay(pick.id) else null

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = checkNotNull(this[APPLICATION_KEY])
                    SearchViewModel(
                        gateway = GraphSearch(application.appGraph),
                        queue = application.appGraph.queue,
                        strings = { id, args -> application.getString(id, *args.toTypedArray()) },
                        log = { message, failed -> Log.w(LOG_TAG, message, failed) },
                    )
                }
            }
    }
}

private class GraphSearch(private val graph: AppGraph) : SearchGateway {
    override fun canSearch(source: SearchSource): Boolean = when (source) {
        SearchSource.YANDEX -> graph.session.state.value.signedIn

        SearchSource.SPOTIFY -> {
            val account = graph.spotifyAccount
            spotifyAccessOf(account.state.value, account.hasSavedLogin()) == SpotifyAccess.LOGGED_IN
        }
    }

    override suspend fun find(source: SearchSource, text: String): Found = when (source) {
        SearchSource.YANDEX -> Found.of(graph.library.search(text))

        SearchSource.SPOTIFY -> {
            val found = graph.spotifyLibrary.search(text)
            Found.of(found, graph.spotifyLibrary.tracks(Found.metadataUris(found)))
        }
    }

    override suspend fun tracksOf(source: SearchSource, pick: FoundPick): List<Track> = when (source) {
        SearchSource.YANDEX -> when (pick.kind) {
            FoundPick.Kind.ARTIST -> graph.library.artistTopTracks(pick.id)
            FoundPick.Kind.ALBUM -> graph.library.albumTracks(pick.id)
        }

        SearchSource.SPOTIFY -> when (pick.kind) {
            FoundPick.Kind.ARTIST -> graph.spotifyLibrary.artistTopTracks(pick.id)
            FoundPick.Kind.ALBUM -> graph.spotifyLibrary.albumTracks(pick.id)
        }
    }

    override fun autoplay(contextUri: String): Continuation = graph.spotifyContinuations.autoplay(contextUri)

    override val jam: StateFlow<JamHostState> get() = graph.jamHost.state

    override val jamEvents: Flow<JamHostEvent> get() = graph.jamHost.events

    override fun jamAdd(track: Track): Boolean = graph.jamHost.add(track)

    override fun jamPlayNext(track: Track): Boolean = graph.jamHost.playNext(track)

    override fun jamCanAdd(track: Track): Boolean = JamTracks.toJam(track) != null
}
