package io.github.kickoman.qiyaa.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.NamedRef
import io.github.kickoman.qiyaa.yandex.PlaylistRef
import io.github.kickoman.qiyaa.yandex.SessionState
import io.github.kickoman.qiyaa.yandex.Station
import io.github.kickoman.qiyaa.yandex.TokenNormalizer
import io.github.kickoman.qiyaa.yandex.WheelWave
import io.github.kickoman.qiyaa.yandex.YandexException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { LOGIN, PLAYER, PLAYLIST, EQ, LIBRARY, JAM, JAM_SETTINGS }

val Screen.backTarget: Screen?
    get() = when (this) {
        Screen.LOGIN, Screen.PLAYER -> null
        Screen.PLAYLIST, Screen.EQ, Screen.LIBRARY, Screen.JAM -> Screen.PLAYER
        Screen.JAM_SETTINGS -> Screen.JAM
    }

enum class LibrarySection { FOR_YOU, WHEEL, STATIONS, PLAYLISTS, ARTISTS, ALBUMS }

sealed interface LoginStatus {
    data object Requesting : LoginStatus

    data object Waiting : LoginStatus

    data object SigningIn : LoginStatus

    data class Failed(val error: ErrorKind) : LoginStatus
}

data class LoginUi(
    val userCode: String = "",
    val verificationUrl: String = "https://ya.ru/device",
    val status: LoginStatus = LoginStatus.Requesting,
    val tokenInput: String = "",
    val tokenError: Boolean = false,
    val notice: String? = null,
)

data class LibraryUi(val wheelMatchesCurrent: Boolean = false, val searchText: String = "")

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    private val library = graph.library
    private val session = graph.session
    private val tokenStore = graph.tokenStore
    private val auth = graph.deviceAuth
    val queue = graph.queue
    val settings = graph.settings

    private val mutableScreen =
        MutableStateFlow(if (tokenStore.load().isEmpty()) Screen.LOGIN else Screen.PLAYER)
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    private val mutableSection = MutableStateFlow<LibrarySection?>(null)
    val section: StateFlow<LibrarySection?> = mutableSection.asStateFlow()

    private val mutableToast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = mutableToast.asStateFlow()
    private var toastJob: Job? = null

    private val mutableLogin = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = mutableLogin.asStateFlow()
    private var loginJob: Job? = null

    private val mutableLibraryUi = MutableStateFlow(LibraryUi())
    val libraryUi: StateFlow<LibraryUi> = mutableLibraryUi.asStateFlow()

    private var wheelSeed = QueueController.MY_WAVE_SEED
    val forYou = listLoader("For you") { library.personalPlaylists() }
    val playlists = listLoader("Playlists") { library.userPlaylists() }
    val artists = listLoader("Artists") { library.likedArtists() }
    val albums = listLoader("Albums") { library.likedAlbums() }
    val stations = listLoader("Stations") { library.stations() }
    val wheel = listLoader("Wheel of waves") { library.wheelWaves(listOf(wheelSeed)) }
    private val lists: List<ListLoader<*>> get() = listOf(forYou, playlists, artists, albums, stations)

    val sessionState: StateFlow<SessionState> = session.state
    val account = library.account
    val likedIds = library.likedIds

    init {
        viewModelScope.launch { queue.events.collect { say(it.render(getApplication())) } }
        viewModelScope.launch {
            session.state.collect { state ->
                when (state) {
                    is SessionState.Online -> loadLibraryLists()
                    SessionState.Expired -> handleExpired()
                    SessionState.LoggedOut, SessionState.Connecting, SessionState.Offline -> {}
                }
            }
        }
        if (mutableScreen.value == Screen.LOGIN) startDeviceLogin()
    }

    fun say(message: String) {
        toastJob?.cancel()
        mutableToast.value = message
        toastJob =
            viewModelScope.launch {
                delay(TOAST_MS)
                mutableToast.value = null
            }
    }

    fun go(screen: Screen) {
        mutableScreen.value = screen
        mutableSection.value = null
    }

    fun openSection(section: LibrarySection) {
        mutableSection.value = section
        loadSection(section)
    }

    fun loadSection(section: LibrarySection) {
        if (!library.isLoggedIn) return
        when (section) {
            LibrarySection.WHEEL -> loadWheel()
            LibrarySection.FOR_YOU -> forYou.load()
            LibrarySection.STATIONS -> stations.load()
            LibrarySection.PLAYLISTS -> playlists.load()
            LibrarySection.ARTISTS -> artists.load()
            LibrarySection.ALBUMS -> albums.load()
        }
    }

    private fun loadWheel() {
        val current = queue.currentWaveSeed()
        wheelSeed = current ?: QueueController.MY_WAVE_SEED
        mutableLibraryUi.update { it.copy(wheelMatchesCurrent = current != null) }
        wheel.load(force = true)
    }

    fun closeSection() {
        mutableSection.value = null
    }

    fun startDeviceLogin() {
        loginJob?.cancel()
        mutableLogin.update { it.copy(userCode = "", status = LoginStatus.Requesting) }
        loginJob =
            viewModelScope.launch {
                try {
                    val code = auth.requestCode()
                    mutableLogin.update {
                        it.copy(
                            userCode = code.userCode,
                            verificationUrl = code.verificationUrl,
                            status = LoginStatus.Waiting,
                        )
                    }
                    val token = auth.waitForToken(code)
                    mutableLogin.update { it.copy(status = LoginStatus.SigningIn) }
                    applyToken(token)
                } catch (failed: YandexException) {
                    mutableLogin.update {
                        it.copy(status = LoginStatus.Failed(kindOf("Device sign-in", failed)))
                    }
                }
            }
    }

    fun setTokenInput(text: String) = mutableLogin.update { it.copy(tokenInput = text, tokenError = false) }

    fun useToken() {
        val token = TokenNormalizer.normalize(mutableLogin.value.tokenInput)
        if (token.isEmpty()) {
            mutableLogin.update { it.copy(tokenError = true) }
            return
        }
        loginJob?.cancel()
        mutableLogin.update { it.copy(status = LoginStatus.SigningIn) }
        viewModelScope.launch { applyToken(token) }
    }

    fun signOut() {
        leaveSession(notice = null)
    }

    fun loadLibraryLists() {
        if (!library.isLoggedIn) return
        for (list in lists) list.load()
        if (likedIds.value.isEmpty()) preloadLikes()
    }

    fun setSearchText(text: String) = mutableLibraryUi.update { it.copy(searchText = text) }

    fun submitSearch() {
        val query = mutableLibraryUi.value.searchText.trim()
        if (query.isEmpty()) return
        mutableLibraryUi.update { it.copy(searchText = "") }
        queue.search(query, string(R.string.queue_search_title, query))
        go(Screen.PLAYER)
    }

    fun playMyWave() {
        queue.playWave(listOf(QueueController.MY_WAVE_SEED), string(R.string.library_my_wave))
        go(Screen.PLAYER)
    }

    fun playLiked() {
        queue.loadSource(string(R.string.library_liked), sourceId = "liked") { library.likedTracks() }
        go(Screen.PLAYER)
    }

    fun playWheelWave(wave: WheelWave) {
        queue.playWave(wave.seeds, wave.name)
        go(Screen.PLAYER)
    }

    fun playStation(station: Station) {
        queue.playWave(listOf(station.id), station.name, sourceId = station.id)
        go(Screen.PLAYER)
    }

    fun playPlaylist(playlist: PlaylistRef) {
        queue.loadSource(playlist.title, sourceId = playlist.sourceId) { library.playlistTracks(playlist) }
        go(Screen.PLAYER)
    }

    fun playSimilar(playlist: PlaylistRef) {
        queue.loadSource(
            string(R.string.queue_similar_title, playlist.title),
            sourceId = playlist.similarSourceId,
        ) {
            library.playlistRecommendations(playlist)
        }
        go(Screen.PLAYER)
    }

    fun playArtist(artist: NamedRef) {
        queue.loadSource(artist.name, sourceId = "artist:${artist.id}") { library.artistTopTracks(artist.id) }
        go(Screen.PLAYER)
    }

    fun playAlbum(album: NamedRef) {
        queue.loadSource(album.name, sourceId = "album:${album.id}") { library.albumTracks(album.id) }
        go(Screen.PLAYER)
    }

    private fun handleExpired() {
        leaveSession(notice = string(R.string.login_expired))
    }

    private fun leaveSession(notice: String?) {
        graph.jamHost.end() // guests search through this account: no account, no jam
        queue.clear()
        session.signOut()
        tokenStore.clear()
        for (list in lists + wheel) list.reset()
        mutableLibraryUi.value = LibraryUi()
        mutableLogin.value = LoginUi(notice = notice)
        go(Screen.LOGIN)
        startDeviceLogin()
    }

    private suspend fun applyToken(token: String) {
        try {
            val account = session.signIn(token)
            tokenStore.save(token)
            mutableLogin.value = LoginUi()
            go(Screen.LIBRARY)
            say(string(R.string.login_signed_in, account.login.ifEmpty { account.displayName }))
        } catch (failed: Exception) {
            val error = kindOf("Sign-in", failed)
            mutableLogin.update { it.copy(status = LoginStatus.Failed(error), tokenError = false) }
            say(string(R.string.login_error, error.render(getApplication())))
        }
    }

    private fun preloadLikes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                library.likedTrackIds()
            } catch (ignored: Exception) {
                // Likes are re-read on the next library load; the screen works without them.
            }
        }
    }

    private fun <T> listLoader(what: String, fetch: suspend () -> List<T>) =
        ListLoader(viewModelScope, Dispatchers.IO, fetch) { failed -> Log.w(LOG_TAG, "$what failed", failed) }

    private fun kindOf(what: String, failed: Exception): ErrorKind {
        Log.w(LOG_TAG, "$what failed", failed)
        return ErrorKind.of(failed)
    }

    private fun string(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    companion object {
        const val TOAST_MS = 1_800L
    }
}

val PlaylistRef.sourceId: String get() = "playlist:$ownerUid:$kind"

val PlaylistRef.similarSourceId: String get() = "similar:$ownerUid:$kind"
