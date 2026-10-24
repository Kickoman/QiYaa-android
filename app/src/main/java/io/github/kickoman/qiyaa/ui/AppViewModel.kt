package io.github.kickoman.qiyaa.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kickoman.qiyaa.AppLocale
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.data.AudioQuality
import io.github.kickoman.qiyaa.data.ShuffleChoice
import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamLink
import io.github.kickoman.qiyaa.local.LocalPick
import io.github.kickoman.qiyaa.local.LocalPicks
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.NamedRef
import io.github.kickoman.qiyaa.yandex.PlaylistRef
import io.github.kickoman.qiyaa.yandex.SessionState
import io.github.kickoman.qiyaa.yandex.Station
import io.github.kickoman.qiyaa.yandex.TokenNormalizer
import io.github.kickoman.qiyaa.yandex.WheelWave
import io.github.kickoman.qiyaa.yandex.YandexException
import io.github.kickoman.qiyaa.yandex.signedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySection {
    FOR_YOU,
    WHEEL,
    STATIONS,
    PLAYLISTS,
    ARTISTS,
    ALBUMS,
    LOCAL_ALBUMS,
    LOCAL_ARTISTS,
    LOCAL_FOLDERS,
}

val LibrarySection.isLocal: Boolean
    get() = this == LibrarySection.LOCAL_ALBUMS || this == LibrarySection.LOCAL_ARTISTS ||
        this == LibrarySection.LOCAL_FOLDERS

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
    val noticeIsError: Boolean = false,
    val jamPageUrl: String? = null,
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
    val telemetryAvailable: Boolean = graph.telemetry != null

    private val jamGuest = graph.jamGuest
    private var pendingLink: JamLink? = null
    private var loginReturn = Screen.LIBRARY

    private val mutableScreen = MutableStateFlow(startScreen(isSignedIn) { jamGuest.restore() })
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

    private val localLibrary = graph.localLibrary
    val localPermission: String = localLibrary.permission
    private val mutableLocalAccess =
        MutableStateFlow(
            LocalAccessRule.before(localLibrary.hasPermission(), settings.localPermissionAsked.value),
        )
    val localAccess: StateFlow<LocalAccess> = mutableLocalAccess.asStateFlow()
    val localRows = listLoader("Local files") { localLibrary.rows() }
    val localPicks: StateFlow<LocalPicks?> =
        localRows.state
            .map { state -> (state as? ListState.Loaded)?.items?.let(::LocalPicks) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        viewModelScope.launch {
            jamGuest.state.collect { state ->
                val inRoom = state.phase == GuestPhase.InRoom || state.phase == GuestPhase.WaitingForRoom
                if (mutableScreen.value == Screen.JAM_GUEST_ADD && !inRoom) go(Screen.JAM_GUEST)
            }
        }
    }

    private val isSignedIn: Boolean get() = session.state.value.signedIn

    fun openJamLink(text: String, launchedFromHistory: Boolean = false): Boolean =
        follow(routeLink(JamLink.find(text), launchedFromHistory))

    private fun routeLink(link: JamLink?, launchedFromHistory: Boolean): LinkAction {
        val host = graph.jamHost.state.value
        val guest = jamGuest.state.value
        return route(
            link,
            HostSide(
                roomId = host.joinUrl?.let(JamLink::find)?.roomId,
                active = host.phase != JamHostPhase.NONE,
                storedSession = host.storedSession,
            ),
            GuestSide(guest.serverUrl, guest.roomId, guest.phase),
            signedIn = isSignedIn,
            launchedFromHistory = launchedFromHistory,
        )
    }

    private fun follow(action: LinkAction): Boolean {
        when (action) {
            LinkAction.Ignore -> return false

            is LinkAction.Refuse -> {
                say(
                    string(
                        when (action.reason) {
                            LinkRefusal.NOT_A_LINK -> R.string.jam_join_bad_link
                            LinkRefusal.HOSTING -> R.string.jam_join_while_hosting
                        },
                    ),
                )
                if (action.reason == LinkRefusal.HOSTING) go(Screen.JAM)
                return false
            }

            is LinkAction.AskSignIn -> {
                openLogin(string(R.string.jam_join_needs_login), jamPage = action.link)
                pendingLink = action.link
            }

            LinkAction.OpenHostJam -> go(Screen.JAM)

            LinkAction.ShowGuest -> go(Screen.JAM_GUEST)

            is LinkAction.OpenGuest -> {
                jamGuest.open(action.link)
                go(Screen.JAM_GUEST)
            }
        }
        return true
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
        if (mutableScreen.value == Screen.LOGIN && screen != Screen.LOGIN) {
            loginJob?.cancel()
            pendingLink = null
        }
        mutableScreen.value = screen
        mutableSection.value = null
    }

    fun openLogin(
        notice: String? = null,
        jamPage: JamLink? = null,
        returnTo: Screen = Screen.LIBRARY,
        noticeIsError: Boolean = false,
    ) {
        if (mutableScreen.value == Screen.LOGIN) {
            mutableLogin.update {
                it.copy(notice = notice, noticeIsError = noticeIsError, jamPageUrl = jamPage?.url)
            }
            return
        }
        loginReturn = returnTo
        mutableLogin.value =
            LoginUi(notice = notice, noticeIsError = noticeIsError, jamPageUrl = jamPage?.url)
        go(Screen.LOGIN)
        startDeviceLogin()
    }

    fun leaveLogin() = go(loginReturn)

    private fun finishLogin(): JamLink? {
        val link = pendingLink
        pendingLink = null
        loginJob = null
        mutableLogin.value = LoginUi()
        go(loginReturn)
        return link
    }

    fun openSection(section: LibrarySection) {
        mutableSection.value = section
        loadSection(section)
    }

    fun loadSection(section: LibrarySection) {
        when (section) {
            LibrarySection.LOCAL_ALBUMS, LibrarySection.LOCAL_ARTISTS, LibrarySection.LOCAL_FOLDERS ->
                refreshLocal()

            LibrarySection.WHEEL -> if (library.isLoggedIn) loadWheel()

            LibrarySection.FOR_YOU -> if (library.isLoggedIn) forYou.load()

            LibrarySection.STATIONS -> if (library.isLoggedIn) stations.load()

            LibrarySection.PLAYLISTS -> if (library.isLoggedIn) playlists.load()

            LibrarySection.ARTISTS -> if (library.isLoggedIn) artists.load()

            LibrarySection.ALBUMS -> if (library.isLoggedIn) albums.load()
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
                    graph.telemetry?.loginFailed()
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
        loginJob = viewModelScope.launch { applyToken(token) }
    }

    /** Stores the interface language and applies it to the app's resources; the screen recreates the activity. */
    fun setLanguage(language: AppLanguage) {
        settings.setLanguage(language)
        AppLocale.apply(getApplication(), language)
    }

    fun setQuality(quality: AudioQuality) = settings.setQuality(quality)

    fun setQualityMetered(quality: AudioQuality) = settings.setQualityMetered(quality)

    fun setContinueWithVibe(on: Boolean) = settings.setContinueWithVibe(on)

    fun setShuffleAlgorithm(choice: ShuffleChoice) = settings.setShuffleAlgorithm(choice)

    fun setTelemetryEnabled(on: Boolean) = settings.setTelemetryEnabled(on)

    fun signOut() {
        leaveSession(expired = false)
    }

    fun loadLibraryLists() {
        if (!library.isLoggedIn) return
        for (list in lists) list.load()
        if (likedIds.value.isEmpty()) preloadLikes()
    }

    fun refreshLocal() {
        val access = LocalAccessRule.before(localLibrary.hasPermission(), settings.localPermissionAsked.value)
        if (access == LocalAccess.GRANTED) {
            onLocalGranted()
        } else if (mutableLocalAccess.value != LocalAccess.SETTINGS) {
            mutableLocalAccess.value = access
        }
    }

    fun onLocalPermission(granted: Boolean, rationale: Boolean) {
        val askedBefore = settings.localPermissionAsked.value
        settings.setLocalPermissionAsked()
        if (granted) {
            onLocalGranted()
        } else {
            mutableLocalAccess.value = LocalAccessRule.after(granted = false, rationale, askedBefore)
        }
    }

    private fun onLocalGranted() {
        if (mutableLocalAccess.value != LocalAccess.GRANTED) {
            mutableLocalAccess.value = LocalAccess.GRANTED
            graph.checkRestoredLocalQueue()
        }
        localRows.load(force = localLibrary.takeChanged())
    }

    fun playLocal(pick: LocalPick, title: String) {
        val picks = localPicks.value ?: return
        queue.loadSource(title, sourceId = sourceIdOf(pick), waveFromHistory = false, limit = LOCAL_LIMIT) {
            picks.tracks(pick)
        }
        go(Screen.PLAYER)
    }

    private fun sourceIdOf(pick: LocalPick): String = when (pick) {
        LocalPick.All -> "local:all"
        is LocalPick.Album -> "local:album:${pick.id}"
        is LocalPick.Artist -> "local:artist:${pick.name}"
        is LocalPick.Folder -> "local:folder:${pick.folder.volume}/${pick.folder.path.joinToString("/")}"
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
        leaveSession(expired = true)
    }

    private fun leaveSession(expired: Boolean) {
        graph.jamHost.cancelCreate()
        graph.jamHost.end()
        if (expired) jamGuest.leave() else jamGuest.forgetAll()
        pendingLink = null
        queue.clear()
        session.signOut()
        tokenStore.clear()
        for (list in lists + wheel) list.reset()
        mutableLibraryUi.value = LibraryUi()
        when (screenAfterSignOut(expired)) {
            Screen.LOGIN -> openLogin(string(R.string.login_expired), noticeIsError = true)

            else -> {
                mutableLogin.value = LoginUi()
                go(Screen.LIBRARY)
            }
        }
    }

    private suspend fun applyToken(token: String) {
        try {
            val account = session.signIn(token)
            tokenStore.save(token)
            val link = finishLogin()
            say(string(R.string.login_signed_in, account.login.ifEmpty { account.displayName }))
            link?.let { follow(routeLink(it, launchedFromHistory = false)) }
        } catch (failed: CancellationException) {
            throw failed
        } catch (failed: Exception) {
            val error = kindOf("Sign-in", failed)
            mutableLogin.update { it.copy(status = LoginStatus.Failed(error), tokenError = false) }
            graph.telemetry?.loginFailed()
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
        const val LOCAL_LIMIT = 10_000
    }
}

val PlaylistRef.sourceId: String get() = "playlist:$ownerUid:$kind"

val PlaylistRef.similarSourceId: String get() = "similar:$ownerUid:$kind"
