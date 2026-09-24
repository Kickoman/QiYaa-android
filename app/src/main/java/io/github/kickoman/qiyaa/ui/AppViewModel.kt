package io.github.kickoman.qiyaa.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.yandex.ApiException
import io.github.kickoman.qiyaa.yandex.DeviceAuth
import io.github.kickoman.qiyaa.yandex.NamedRef
import io.github.kickoman.qiyaa.yandex.PlaylistRef
import io.github.kickoman.qiyaa.yandex.Station
import io.github.kickoman.qiyaa.yandex.TokenNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { LOGIN, PLAYER, PLAYLIST, EQ, LIBRARY }
enum class LibrarySub { STATIONS, PLAYLISTS, ARTISTS, ALBUMS }

sealed interface LoginStatus {
    data object Requesting : LoginStatus
    data object Waiting : LoginStatus
    data object SigningIn : LoginStatus
    data class Failed(val message: String) : LoginStatus
}

data class LoginUi(
    val userCode: String = "",
    val verificationUrl: String = "https://ya.ru/device",
    val status: LoginStatus = LoginStatus.Requesting,
    val tokenInput: String = "",
    val tokenError: Boolean = false,
)

/** Lazily loaded library lists; `null` = not loaded yet. */
data class LibraryUi(
    val playlists: List<PlaylistRef>? = null,
    val artists: List<NamedRef>? = null,
    val albums: List<NamedRef>? = null,
    val stations: List<Station>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val search: String = "",
)

/** Navigation, sign-in and the library lists. Playback lives in [PlayerViewModel]. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.appGraph
    private val library = graph.library
    private val api = graph.api
    private val tokenStore = graph.tokenStore
    private val auth = graph.deviceAuth
    val queue = graph.queue
    val settings = graph.settings

    private val _screen = MutableStateFlow(if (tokenStore.load().isEmpty()) Screen.LOGIN else Screen.PLAYER)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _sub = MutableStateFlow<LibrarySub?>(null)
    val sub: StateFlow<LibrarySub?> = _sub.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private var toastJob: Job? = null

    private val _login = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = _login.asStateFlow()
    private var loginJob: Job? = null

    private val _lib = MutableStateFlow(LibraryUi())
    val lib: StateFlow<LibraryUi> = _lib.asStateFlow()

    val account = library.account
    val likedIds = library.likedIds

    init {
        viewModelScope.launch { queue.messages.collect(::say) }
        if (_screen.value == Screen.LOGIN) startDeviceLogin() else restoreSession()
    }

    fun say(msg: String) {
        toastJob?.cancel()
        _toast.value = msg
        toastJob = viewModelScope.launch {
            delay(1800)
            _toast.value = null
        }
    }

    // ------------------------------------------------------------------ navigation

    fun go(screen: Screen) {
        _screen.value = screen
        _sub.value = null
    }

    fun openSub(sub: LibrarySub) {
        _sub.value = sub
        loadLibraryLists()
    }

    fun closeSub() {
        _sub.value = null
    }

    // ------------------------------------------------------------------ sign-in

    private fun restoreSession() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { library.connectAccount() }
                preloadLikes()
            } catch (e: ApiException) {
                val m = e.message.orEmpty()
                if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403") || m.contains("not authorized")) {
                    signOut()
                } else {
                    say(m)
                }
            } catch (e: Exception) {
                say("Error: ${e.message}")
            }
        }
    }

    fun startDeviceLogin() {
        loginJob?.cancel()
        _login.update { it.copy(userCode = "", status = LoginStatus.Requesting) }
        loginJob = viewModelScope.launch {
            try {
                val code = auth.requestCode()
                _login.update { it.copy(userCode = code.userCode, verificationUrl = code.verificationUrl, status = LoginStatus.Waiting) }
                val token = auth.waitForToken(code)
                _login.update { it.copy(status = LoginStatus.SigningIn) }
                applyToken(token)
            } catch (e: DeviceAuth.AuthException) {
                _login.update { it.copy(status = LoginStatus.Failed(e.message ?: "error")) }
            }
        }
    }

    fun setTokenInput(text: String) = _login.update { it.copy(tokenInput = text, tokenError = false) }

    fun useToken() {
        val token = TokenNormalizer.normalize(_login.value.tokenInput)
        if (token.isEmpty()) {
            _login.update { it.copy(tokenError = true) }
            return
        }
        loginJob?.cancel()
        _login.update { it.copy(status = LoginStatus.SigningIn) }
        viewModelScope.launch { applyToken(token) }
    }

    private suspend fun applyToken(token: String) {
        api.token = token
        try {
            val acc = withContext(Dispatchers.IO) { library.connectAccount() }
            tokenStore.save(token)
            _login.value = LoginUi()
            go(Screen.LIBRARY)
            say(getApplication<Application>().getString(R.string.login_signed_in, acc.login.ifEmpty { acc.displayName }))
            preloadLikes()
            loadLibraryLists(force = true)
        } catch (e: Exception) {
            api.token = ""
            _login.update { it.copy(status = LoginStatus.Failed(e.message ?: "error"), tokenError = false) }
            say(getApplication<Application>().getString(R.string.login_error, e.message))
        }
    }

    /** The desktop app loads "Liked" right after login so like/unlike state is known. */
    private fun preloadLikes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                library.likedTrackIds()
            } catch (_: Exception) {
            }
        }
    }

    fun signOut() {
        queue.clear()
        library.logout()
        tokenStore.clear()
        _lib.value = LibraryUi()
        go(Screen.LOGIN)
        startDeviceLogin()
    }

    // ------------------------------------------------------------------ library

    fun loadLibraryLists(force: Boolean = false) {
        val cur = _lib.value
        if (!library.isLoggedIn) return
        if (cur.loading) return
        if (!force && cur.playlists != null && cur.artists != null && cur.albums != null && cur.stations != null) return
        _lib.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val playlists = library.userPlaylists()
                    val artists = library.likedArtists()
                    val albums = library.likedAlbums()
                    val stations = library.stations()
                    _lib.update { it.copy(playlists = playlists, artists = artists, albums = albums, stations = stations, loading = false) }
                }
                if (likedIds.value.isEmpty()) preloadLikes()
            } catch (e: Exception) {
                _lib.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun setSearch(text: String) = _lib.update { it.copy(search = text) }

    fun submitSearch() {
        val q = _lib.value.search.trim()
        if (q.isEmpty()) return
        _lib.update { it.copy(search = "") }
        queue.search(q)
        go(Screen.PLAYER)
    }

    fun playMyWave() {
        queue.playMyWave()
        go(Screen.PLAYER)
    }

    fun playLiked() {
        val title = getApplication<Application>().getString(R.string.library_liked)
        queue.loadSource(title, sourceId = "liked") { library.likedTracks() }
        go(Screen.PLAYER)
    }

    fun playStation(s: Station) {
        queue.playWave(listOf(s.id), s.name, sourceId = s.id)
        go(Screen.PLAYER)
    }

    fun playPlaylist(p: PlaylistRef) {
        queue.loadSource(p.title, sourceId = "playlist:${p.ownerUid}:${p.kind}") { library.playlistTracks(p) }
        go(Screen.PLAYER)
    }

    fun playArtist(a: NamedRef) {
        queue.loadSource(a.name, sourceId = "artist:${a.id}") { library.artistTopTracks(a.id) }
        go(Screen.PLAYER)
    }

    fun playAlbum(a: NamedRef) {
        queue.loadSource(a.name, sourceId = "album:${a.id}") { library.albumTracks(a.id) }
        go(Screen.PLAYER)
    }
}
