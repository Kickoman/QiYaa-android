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
import io.github.kickoman.qiyaa.SpotifyLoginWay
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.errorKindOf
import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.QueueState
import io.github.kickoman.qiyaa.queue.SearchPick
import io.github.kickoman.qiyaa.spotify.BrowserLogin
import io.github.kickoman.qiyaa.spotify.PairLogin
import io.github.kickoman.qiyaa.spotify.SpotifyItem
import io.github.kickoman.qiyaa.spotify.SpotifyState
import io.github.kickoman.qiyaa.spotify.SpotifyTracks
import io.github.kickoman.qiyaa.spotifyLoginErrorKindOf
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.ErrorKind
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SpotifyList { FOR_YOU, PLAYLISTS, ALBUMS, ARTISTS }

enum class SpotifyAccess { LOG_IN, CONNECTING, LOGGED_IN, EXPIRED, PREMIUM_REQUIRED, REFUSED }

data class SpotifyUi(
    val access: SpotifyAccess = SpotifyAccess.LOG_IN,
    val canLogOut: Boolean = false,
    val username: String = "",
    val searchText: String = "",
)

sealed interface SpotifyCodeStatus {
    data object Requesting : SpotifyCodeStatus

    data object Waiting : SpotifyCodeStatus

    data class Failed(val error: ErrorKind) : SpotifyCodeStatus
}

sealed interface SpotifyBrowserStatus {
    data object Idle : SpotifyBrowserStatus

    data object Waiting : SpotifyBrowserStatus

    data class Failed(val error: ErrorKind) : SpotifyBrowserStatus
}

data class SpotifyLoginUi(
    val userCode: String = "",
    val pairUrl: String = "",
    val code: SpotifyCodeStatus = SpotifyCodeStatus.Requesting,
    val browser: SpotifyBrowserStatus = SpotifyBrowserStatus.Idle,
)

interface SpotifyGateway {
    val state: StateFlow<SpotifyState>
    val onScreen: StateFlow<Boolean>
    val username: String
    val likedSongsUri: String

    fun hasSavedLogin(): Boolean

    fun login(accessToken: String)

    fun logout()

    suspend fun requestCode(): PairLogin.Code

    suspend fun waitForToken(code: PairLogin.Code): String

    suspend fun loginInBrowser(pages: BrowserLogin.Pages, openPage: (String) -> Unit): String

    suspend fun likedTracks(): List<Track>

    suspend fun forYou(): List<SpotifyItem>

    suspend fun playlists(): List<SpotifyItem>

    suspend fun savedAlbums(): List<SpotifyItem>

    suspend fun followedArtists(): List<SpotifyItem>

    suspend fun playlistTracks(uri: String): List<Track>

    suspend fun albumTracks(uri: String): List<Track>

    suspend fun artistTopTracks(uri: String): List<Track>

    fun autoplay(contextUri: String): Continuation

    fun station(trackUri: String): Continuation

    suspend fun searchPick(text: String): SearchPick

    fun loggedIn(way: SpotifyLoginWay)

    fun loggedOut()
}

fun spotifyCanLogOut(access: SpotifyAccess, savedLogin: Boolean): Boolean = when (access) {
    SpotifyAccess.LOG_IN, SpotifyAccess.EXPIRED -> false
    SpotifyAccess.LOGGED_IN -> true
    SpotifyAccess.CONNECTING, SpotifyAccess.PREMIUM_REQUIRED, SpotifyAccess.REFUSED -> savedLogin
}

fun spotifyAccessOf(state: SpotifyState, savedLogin: Boolean): SpotifyAccess = when (state) {
    SpotifyState.LoggedOut -> SpotifyAccess.LOG_IN

    SpotifyState.Connecting, SpotifyState.Offline ->
        if (savedLogin) SpotifyAccess.LOGGED_IN else SpotifyAccess.CONNECTING

    is SpotifyState.Online -> SpotifyAccess.LOGGED_IN

    SpotifyState.Expired -> SpotifyAccess.EXPIRED

    SpotifyState.PremiumRequired -> SpotifyAccess.PREMIUM_REQUIRED

    is SpotifyState.Refused -> SpotifyAccess.REFUSED
}

class SpotifyViewModel(
    private val spotify: SpotifyGateway,
    private val queue: QueueController,
    private val strings: (Int, List<Any>) -> String,
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) : ViewModel() {
    private val mutableUi = MutableStateFlow(SpotifyUi())
    val ui: StateFlow<SpotifyUi> = mutableUi.asStateFlow()

    private val mutableLogin = MutableStateFlow(SpotifyLoginUi())
    val login: StateFlow<SpotifyLoginUi> = mutableLogin.asStateFlow()

    private val mutableNotices = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = mutableNotices.receiveAsFlow()

    private val mutableMoves = Channel<Screen>(Channel.BUFFERED)
    val moves: Flow<Screen> = mutableMoves.receiveAsFlow()

    private val mutablePages = Channel<String>(Channel.CONFLATED)
    val browserPages: Flow<String> = mutablePages.receiveAsFlow()

    val queueState: StateFlow<QueueState> = queue.state

    val forYou = listLoader("For you") { spotify.forYou() }
    val playlists = listLoader("Playlists") { spotify.playlists() }
    val albums = listLoader("Albums") { spotify.savedAlbums() }
    val artists = listLoader("Artists") { spotify.followedArtists() }
    private val lists: List<ListLoader<SpotifyItem>> get() = listOf(forYou, playlists, albums, artists)

    private var codeJob: Job? = null
    private var browserJob: Job? = null
    private var pendingWay: SpotifyLoginWay? = null
    private var heldNotice: Job? = null

    init {
        viewModelScope.launch {
            var first = true
            spotify.state.collect { state ->
                onState(state, announce = !first)
                first = false
            }
        }
    }

    fun list(which: SpotifyList): ListLoader<SpotifyItem> = when (which) {
        SpotifyList.FOR_YOU -> forYou
        SpotifyList.PLAYLISTS -> playlists
        SpotifyList.ALBUMS -> albums
        SpotifyList.ARTISTS -> artists
    }

    fun load(which: SpotifyList) {
        if (mutableUi.value.access == SpotifyAccess.LOGGED_IN) list(which).load()
    }

    fun loadLists() {
        if (mutableUi.value.access == SpotifyAccess.LOGGED_IN) for (list in lists) list.load()
    }

    fun openLogin() {
        cancelLogin()
        mutableLogin.value = SpotifyLoginUi()
        startCodeLogin()
        move(Screen.SPOTIFY_LOGIN)
    }

    fun leaveLogin() {
        cancelLogin()
        move(Screen.LIBRARY)
    }

    fun cancelLogin() {
        codeJob?.cancel()
        browserJob?.cancel()
        codeJob = null
        browserJob = null
    }

    fun startCodeLogin() {
        codeJob?.cancel()
        mutableLogin.update { it.copy(userCode = "", pairUrl = "", code = SpotifyCodeStatus.Requesting) }
        codeJob =
            viewModelScope.launch {
                try {
                    val code = withContext(io) { spotify.requestCode() }
                    mutableLogin.update {
                        it.copy(
                            userCode = code.userCode,
                            pairUrl = code.verificationUrl,
                            code = SpotifyCodeStatus.Waiting,
                        )
                    }
                    val token = withContext(io) { spotify.waitForToken(code) }
                    finishLogin(token, SpotifyLoginWay.CODE)
                } catch (failed: CancellationException) {
                    throw failed
                } catch (failed: Exception) {
                    log("Spotify code login failed", failed)
                    val error = spotifyLoginErrorKindOf(failed)
                    mutableLogin.update { it.copy(code = SpotifyCodeStatus.Failed(error)) }
                }
            }
    }

    fun startBrowserLogin() {
        browserJob?.cancel()
        mutableLogin.update { it.copy(browser = SpotifyBrowserStatus.Waiting) }
        val pages =
            BrowserLogin.Pages(
                loggingIn = string(R.string.spotify_page_logging_in),
                refused = string(R.string.spotify_page_refused),
            )
        browserJob =
            viewModelScope.launch {
                try {
                    val token = withContext(io) { spotify.loginInBrowser(pages) { mutablePages.trySend(it) } }
                    finishLogin(token, SpotifyLoginWay.BROWSER)
                } catch (failed: CancellationException) {
                    throw failed
                } catch (failed: Exception) {
                    log("Spotify browser login failed", failed)
                    val error = spotifyLoginErrorKindOf(failed)
                    mutableLogin.update { it.copy(browser = SpotifyBrowserStatus.Failed(error)) }
                }
            }
    }

    fun noBrowser() {
        browserJob?.cancel()
        browserJob = null
        mutableLogin.update { it.copy(browser = SpotifyBrowserStatus.Idle) }
        notice(string(R.string.login_no_browser))
    }

    fun say(message: String) = notice(message)

    fun logout() {
        cancelLogin()
        pendingWay = null
        spotify.logout()
        spotify.loggedOut()
        notice(string(R.string.spotify_logged_out))
    }

    fun setSearchText(text: String) = mutableUi.update { it.copy(searchText = text) }

    fun submitSearch() {
        val text = mutableUi.value.searchText.trim()
        if (text.isEmpty()) return
        mutableUi.update { it.copy(searchText = "") }
        queue.search(text, string(R.string.queue_search_title, text), spotify::searchPick)
        move(Screen.PLAYER)
    }

    fun playLiked() {
        val title = string(R.string.spotify_liked_songs)
        notice(string(R.string.queue_loading, title))
        val continuation = spotify.autoplay(spotify.likedSongsUri)
        queue.loadSource(title, sourceId = LIKED_SONGS, continuation = continuation) { spotify.likedTracks() }
        move(Screen.PLAYER)
    }

    fun play(which: SpotifyList, item: SpotifyItem) {
        queue.loadSource(item.name, sourceId = item.uri, continuation = spotify.autoplay(item.uri)) {
            when (which) {
                SpotifyList.FOR_YOU, SpotifyList.PLAYLISTS -> spotify.playlistTracks(item.uri)
                SpotifyList.ALBUMS -> spotify.albumTracks(item.uri)
                SpotifyList.ARTISTS -> spotify.artistTopTracks(item.uri)
            }
        }
        move(Screen.PLAYER)
    }

    fun playRadio(track: Track) {
        queue.playContinuation(
            string(R.string.spotify_radio_title, track.title),
            RADIO_PREFIX + track.id,
            spotify.station(SpotifyTracks.uriOf(track)),
        )
    }

    private fun finishLogin(token: String, way: SpotifyLoginWay) {
        pendingWay = way
        spotify.login(token)
        notice(string(R.string.spotify_connecting))
        cancelLogin()
        mutableLogin.value = SpotifyLoginUi()
        move(Screen.LIBRARY)
    }

    private fun onState(state: SpotifyState, announce: Boolean) {
        val savedLogin = spotify.hasSavedLogin()
        val access = spotifyAccessOf(state, savedLogin)
        val username = (state as? SpotifyState.Online)?.username ?: spotify.username
        val canLogOut = spotifyCanLogOut(access, savedLogin)
        mutableUi.update { it.copy(access = access, canLogOut = canLogOut, username = username) }
        if (access != SpotifyAccess.LOGGED_IN) for (list in lists) list.reset()
        when (state) {
            is SpotifyState.Online -> {
                for (list in lists) if (list.state.value is ListState.Failed) list.load()
                pendingWay?.let { way ->
                    pendingWay = null
                    spotify.loggedIn(way)
                    noticeOnScreen(string(R.string.spotify_logged_in, username))
                }
            }

            SpotifyState.Expired -> refused(R.string.spotify_expired, announce)

            SpotifyState.PremiumRequired -> refused(R.string.spotify_premium_required, announce)

            is SpotifyState.Refused -> refused(R.string.error_spotify_login_refused, announce)

            SpotifyState.LoggedOut, SpotifyState.Connecting, SpotifyState.Offline -> {}
        }
    }

    private fun refused(text: Int, announce: Boolean) {
        pendingWay = null
        if (announce) noticeOnScreen(string(text))
    }

    private fun notice(message: String) {
        mutableNotices.trySend(message)
    }

    private fun noticeOnScreen(message: String) {
        heldNotice?.cancel()
        heldNotice =
            viewModelScope.launch {
                spotify.onScreen.first { it }
                notice(message)
            }
    }

    private fun move(screen: Screen) {
        mutableMoves.trySend(screen)
    }

    private fun string(id: Int, vararg args: Any): String = strings(id, args.toList())

    private fun <T> listLoader(what: String, fetch: suspend () -> List<T>) = ListLoader(
        viewModelScope,
        io,
        fetch,
        logFailure = { failed -> log("Spotify: $what failed", failed) },
        errorKindOf = ::errorKindOf,
    )

    companion object {
        const val LIKED_SONGS = "spotify:liked"
        const val RADIO_PREFIX = "spotify:radio:"

        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = checkNotNull(this[APPLICATION_KEY])
                    SpotifyViewModel(
                        spotify = GraphSpotify(application.appGraph),
                        queue = application.appGraph.queue,
                        strings = { id, args -> application.getString(id, *args.toTypedArray()) },
                        log = { message, failed -> Log.w(LOG_TAG, message, failed) },
                    )
                }
            }
    }
}

private class GraphSpotify(private val graph: AppGraph) : SpotifyGateway {
    override val state: StateFlow<SpotifyState> get() = graph.spotifyAccount.state
    override val onScreen: StateFlow<Boolean> get() = graph.screen.onScreen
    override val username: String get() = graph.spotifyLibrary.username
    override val likedSongsUri: String get() = graph.spotifyLibrary.likedSongsUri

    override fun hasSavedLogin(): Boolean = graph.spotifyAccount.hasSavedLogin()

    override fun login(accessToken: String) = graph.spotifyAccount.login(accessToken)

    override fun logout() = graph.spotifyAccount.logout()

    override suspend fun requestCode(): PairLogin.Code = graph.spotifyPairLogin.requestCode()

    override suspend fun waitForToken(code: PairLogin.Code): String =
        graph.spotifyPairLogin.waitForToken(code)

    override suspend fun loginInBrowser(pages: BrowserLogin.Pages, openPage: (String) -> Unit): String =
        graph.spotifyBrowserLogin.login(pages, openPage)

    override suspend fun likedTracks(): List<Track> = graph.spotifyLibrary.likedTracks()

    override suspend fun forYou(): List<SpotifyItem> = graph.spotifyLibrary.forYou()

    override suspend fun playlists(): List<SpotifyItem> = graph.spotifyLibrary.playlists()

    override suspend fun savedAlbums(): List<SpotifyItem> = graph.spotifyLibrary.savedAlbums()

    override suspend fun followedArtists(): List<SpotifyItem> = graph.spotifyLibrary.followedArtists()

    override suspend fun playlistTracks(uri: String): List<Track> = graph.spotifyLibrary.playlistTracks(uri)

    override suspend fun albumTracks(uri: String): List<Track> = graph.spotifyLibrary.albumTracks(uri)

    override suspend fun artistTopTracks(uri: String): List<Track> = graph.spotifyLibrary.artistTopTracks(uri)

    override fun autoplay(contextUri: String): Continuation = graph.spotifyContinuations.autoplay(contextUri)

    override fun station(trackUri: String): Continuation = graph.spotifyContinuations.station(trackUri)

    override suspend fun searchPick(text: String): SearchPick = graph.spotifySearch(text)

    override fun loggedIn(way: SpotifyLoginWay) {
        graph.telemetry?.spotifyLoggedIn(way)
    }

    override fun loggedOut() {
        graph.telemetry?.spotifyLoggedOut()
    }
}
