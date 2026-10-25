package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.SpotifyLoginWay
import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.ContinuationKey
import io.github.kickoman.qiyaa.queue.QueueEvent
import io.github.kickoman.qiyaa.queue.SearchPick
import io.github.kickoman.qiyaa.spotify.AuthorizationException
import io.github.kickoman.qiyaa.spotify.BrowserLogin
import io.github.kickoman.qiyaa.spotify.NotLoggedInException
import io.github.kickoman.qiyaa.spotify.PairLogin
import io.github.kickoman.qiyaa.spotify.SpotifyHttpException
import io.github.kickoman.qiyaa.spotify.SpotifyItem
import io.github.kickoman.qiyaa.spotify.SpotifyState
import io.github.kickoman.qiyaa.support.FakeContinuation
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.ErrorKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyViewModelTest {
    private val spotify = FakeSpotify()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `SPOT-01 SPOT-03 TEL-18 the code login shows the code, logs in, and counts once welcomed`() =
        runTest {
            val subject = Subject(this)
            val token = CompletableDeferred<String>()
            spotify.onToken = { token.await() }

            subject.model.openLogin()
            runCurrent()

            assertEquals(listOf(Screen.SPOTIFY_LOGIN.name), subject.moves)
            val login = subject.model.login.value
            assertEquals(SpotifyLoginUi("ABCD-EFGH", PAIR_URL, SpotifyCodeStatus.Waiting), login)
            token.complete("token-1")
            runCurrent()
            assertEquals(listOf("code", "login token-1"), spotify.calls)
            assertEquals(listOf(Screen.SPOTIFY_LOGIN.name, Screen.LIBRARY.name), subject.moves)
            assertEquals(listOf("spotify_connecting"), subject.notices)
            spotify.state.value = SpotifyState.Connecting
            runCurrent()
            assertEquals(SpotifyAccess.CONNECTING, subject.model.ui.value.access)
            spotify.saved = true
            spotify.state.value = SpotifyState.Online("me")
            runCurrent()
            assertEquals(listOf("code", "login token-1", "telemetry CODE"), spotify.calls)
            assertEquals("spotify_logged_in(me)", subject.notices.last())
            assertEquals(SpotifyUi(SpotifyAccess.LOGGED_IN, "me"), subject.model.ui.value)
            spotify.state.value = SpotifyState.Offline
            runCurrent()
            spotify.state.value = SpotifyState.Online("me")
            runCurrent()
            assertEquals(1, spotify.calls.count { it.startsWith("telemetry") })
        }

    @Test
    fun `SPOT-02 TEL-18 the browser login opens its page with the app's texts and stops the code login`() =
        runTest {
            val subject = Subject(this)
            val token = CompletableDeferred<String>()
            spotify.onToken = { waitForever("token") }
            spotify.onBrowser = { pages, openPage ->
                spotify.calls += "pages ${pages.loggingIn} ${pages.refused}"
                openPage("https://accounts.spotify.com/authorize?state=s")
                token.await()
            }
            subject.model.openLogin()
            runCurrent()

            subject.model.startBrowserLogin()
            runCurrent()

            assertEquals(SpotifyBrowserStatus.Waiting, subject.model.login.value.browser)
            assertEquals(listOf("https://accounts.spotify.com/authorize?state=s"), subject.pages)
            token.complete("token-2")
            runCurrent()
            val expected =
                listOf(
                    "code",
                    "pages spotify_page_logging_in spotify_page_refused",
                    "login token-2",
                    "token cancelled",
                )
            assertEquals(expected, spotify.calls)
            assertEquals(Screen.LIBRARY.name, subject.moves.last())
            spotify.state.value = SpotifyState.Online("me")
            runCurrent()
            assertEquals("telemetry BROWSER", spotify.calls.last())
        }

    @Test
    fun `SPOT-01 a failed code says why, and a tap on it asks for a new one`() = runTest {
        val subject = Subject(this)
        spotify.onToken = { throw AuthorizationException(PairLogin.EXPIRED_TOKEN, "POST /api/token") }
        subject.model.openLogin()
        runCurrent()
        assertEquals(SpotifyCodeStatus.Failed(ErrorKind.CodeExpired), subject.model.login.value.code)

        spotify.onToken = { throw AuthorizationException("access_denied", "POST /api/token") }
        subject.model.startCodeLogin()
        assertEquals(SpotifyCodeStatus.Requesting, subject.model.login.value.code)
        assertEquals("", subject.model.login.value.userCode)
        runCurrent()

        assertEquals(SpotifyCodeStatus.Failed(ErrorKind.SpotifyLoginRefused), subject.model.login.value.code)
        assertEquals(listOf("code", "code"), spotify.calls)
        assertFalse(spotify.calls.any { it.startsWith("login") })
    }

    @Test
    fun `SPOT-02 a browser login refused by Spotify says so and saves nothing`() = runTest {
        val subject = Subject(this)
        spotify.onBrowser = { _, _ -> throw AuthorizationException("state_mismatch", "GET /login") }
        subject.model.openLogin()
        subject.model.startBrowserLogin()
        runCurrent()

        val refused = SpotifyBrowserStatus.Failed(ErrorKind.SpotifyLoginRefused)
        assertEquals(refused, subject.model.login.value.browser)
        assertEquals(SpotifyCodeStatus.Waiting, subject.model.login.value.code)
        assertFalse(spotify.calls.any { it.startsWith("login") })
    }

    @Test
    fun `Later and leaving the screen cancel both ways`() = runTest {
        val subject = Subject(this)
        spotify.onToken = { waitForever("token") }
        spotify.onBrowser = { _, _ -> waitForever("browser") }
        subject.model.openLogin()
        subject.model.startBrowserLogin()
        runCurrent()

        subject.model.leaveLogin()
        runCurrent()

        assertEquals(listOf("code", "token cancelled", "browser cancelled"), spotify.calls)
        assertEquals(listOf(Screen.SPOTIFY_LOGIN.name, Screen.LIBRARY.name), subject.moves)
    }

    @Test
    fun `without a browser the browser login stops and says so`() = runTest {
        val subject = Subject(this)
        spotify.onBrowser = { _, openPage ->
            openPage("https://accounts.spotify.com/authorize")
            waitForever("browser")
        }
        subject.model.startBrowserLogin()
        runCurrent()

        subject.model.noBrowser()
        runCurrent()

        assertEquals(listOf("browser cancelled"), spotify.calls)
        assertEquals(SpotifyBrowserStatus.Idle, subject.model.login.value.browser)
        assertEquals(listOf("login_no_browser"), subject.notices)
    }

    @Test
    fun `SPOT-04 SPOT-05 a refusal after the first state says why, and the library offers to log in again`() =
        runTest {
            val subject = Subject(this)

            spotify.state.value = SpotifyState.Expired
            runCurrent()
            assertEquals(SpotifyAccess.EXPIRED, subject.model.ui.value.access)
            spotify.state.value = SpotifyState.PremiumRequired
            runCurrent()
            assertEquals(SpotifyAccess.PREMIUM_REQUIRED, subject.model.ui.value.access)
            spotify.state.value = SpotifyState.Refused(9)
            runCurrent()

            assertEquals(SpotifyAccess.REFUSED, subject.model.ui.value.access)
            assertEquals(
                listOf("spotify_expired", "spotify_premium_required", "error_spotify_login_refused"),
                subject.notices,
            )
        }

    @Test
    fun `a view model that finds the login already expired shows it without a status`() = runTest {
        spotify.state.value = SpotifyState.Expired
        val subject = Subject(this)

        assertEquals(SpotifyAccess.EXPIRED, subject.model.ui.value.access)
        assertEquals(emptyList<String>(), subject.notices)
    }

    @Test
    fun `a refused token login is not counted as a login`() = runTest {
        val subject = Subject(this)
        spotify.onToken = { "token-3" }
        subject.model.openLogin()
        runCurrent()

        spotify.state.value = SpotifyState.PremiumRequired
        runCurrent()
        spotify.state.value = SpotifyState.Online("other")
        runCurrent()

        assertFalse(spotify.calls.any { it.startsWith("telemetry") })
    }

    @Test
    fun `SPOT-04 a saved login is logged in while connecting, a fresh token only once online`() {
        assertEquals(SpotifyAccess.LOGGED_IN, spotifyAccessOf(SpotifyState.Connecting, savedLogin = true))
        assertEquals(SpotifyAccess.LOGGED_IN, spotifyAccessOf(SpotifyState.Offline, savedLogin = true))
        assertEquals(SpotifyAccess.CONNECTING, spotifyAccessOf(SpotifyState.Connecting, savedLogin = false))
        assertEquals(SpotifyAccess.CONNECTING, spotifyAccessOf(SpotifyState.Offline, savedLogin = false))
        assertEquals(SpotifyAccess.LOGGED_IN, spotifyAccessOf(SpotifyState.Online("me"), savedLogin = false))
        assertEquals(SpotifyAccess.LOG_IN, spotifyAccessOf(SpotifyState.LoggedOut, savedLogin = false))
        assertEquals(SpotifyAccess.EXPIRED, spotifyAccessOf(SpotifyState.Expired, savedLogin = false))
        assertEquals(SpotifyAccess.REFUSED, spotifyAccessOf(SpotifyState.Refused(9), savedLogin = true))
    }

    @Test
    fun `SPOT-05 SPOT-06 log out is offered whenever a login is kept, log in when none is`() {
        val canLogOut = SpotifyAccess.entries.filter { it.canLogOut }
        val expected =
            listOf(
                SpotifyAccess.CONNECTING,
                SpotifyAccess.LOGGED_IN,
                SpotifyAccess.PREMIUM_REQUIRED,
                SpotifyAccess.REFUSED,
            )
        assertEquals(expected, canLogOut)
    }

    @Test
    fun `SPOT-06 TEL-18 logging out forgets the lists, leaves the queue and counts the logout`() = runTest {
        val subject = Subject(this)
        loggedIn()
        subject.model.loadLists()
        runCurrent()
        assertTrue(subject.model.playlists.state.value is ListState.Loaded)
        subject.harness.controller.setQueue(
            listOf(spotify("q1"), spotify("q2")),
            "Liked Songs",
            isWave = false,
            autoplay = false,
            sourceId = null,
        )

        subject.model.logout()
        runCurrent()

        assertEquals(listOf("logout", "telemetry logout"), spotify.calls.takeLast(2))
        assertEquals(listOf("spotify_logged_out"), subject.notices)
        assertEquals(SpotifyAccess.LOG_IN, subject.model.ui.value.access)
        for (list in SpotifyList.entries) assertEquals(ListState.Idle, subject.model.list(list).state.value)
        assertEquals(listOf("q1", "q2"), subject.harness.controller.state.value.tracks.map(Track::id))
    }

    @Test
    fun `the lists load only with a login, and fail with the kinds of Spotify's errors`() = runTest {
        val subject = Subject(this)
        subject.model.loadLists()
        subject.model.load(SpotifyList.ALBUMS)
        runCurrent()
        assertEquals(emptyList<String>(), spotify.calls)

        loggedIn()
        spotify.onPlaylists = { throw SpotifyHttpException(503, "GET", "/playlist/v2/user/{username}") }
        spotify.onArtists = { throw NotLoggedInException() }
        subject.model.loadLists()
        runCurrent()

        assertEquals(listOf("forYou", "playlists", "savedAlbums", "followedArtists"), spotify.calls)
        assertEquals(ListState.Loaded(listOf(FOR_YOU)), subject.model.forYou.state.value)
        assertEquals(ListState.Failed(ErrorKind.ServerError(503)), subject.model.playlists.state.value)
        assertEquals(ListState.Failed(ErrorKind.SpotifyLoggedOut), subject.model.artists.state.value)
        spotify.onPlaylists = { listOf(PLAYLIST) }
        subject.model.load(SpotifyList.PLAYLISTS)
        runCurrent()
        assertEquals(ListState.Loaded(listOf(PLAYLIST)), subject.model.playlists.state.value)
    }

    @Test
    fun `SPOT-07 SPOT-20 Liked Songs says it loads, then plays with the collection's autoplay`() = runTest {
        val subject = Subject(this)
        loggedIn()

        subject.model.playLiked()
        runCurrent()

        assertEquals(listOf("queue_loading(spotify_liked_songs)"), subject.notices)
        assertEquals(listOf("autoplay spotify:user:me:collection", "likedTracks"), spotify.calls)
        val queue = subject.harness.controller.state.value
        assertEquals("spotify_liked_songs", queue.title)
        assertEquals(SpotifyViewModel.LIKED_SONGS, queue.activeSourceId)
        assertEquals(QueueEvent.SourceLoaded("spotify_liked_songs", 2), subject.harness.events.last())
        assertEquals(listOf(Screen.PLAYER.name), subject.moves)
        val saved = subject.harness.store.snapshot()?.continuation
        assertEquals(ContinuationKey("spotify-autoplay", "spotify:user:me:collection"), saved)
    }

    @Test
    fun `SPOT-08 SPOT-09 SPOT-10 playlists, albums and artists play under their names with autoplay`() =
        runTest {
            val subject = Subject(this)
            loggedIn()
            val picks =
                listOf(
                    SpotifyList.FOR_YOU to FOR_YOU,
                    SpotifyList.PLAYLISTS to PLAYLIST,
                    SpotifyList.ALBUMS to SpotifyItem("spotify:album:a1", "Группа крови"),
                    SpotifyList.ARTISTS to SpotifyItem("spotify:artist:k1", "Кино"),
                )

            for ((list, item) in picks) {
                subject.model.play(list, item)
                runCurrent()
                val queue = subject.harness.controller.state.value
                assertEquals(item.name, queue.title)
                assertEquals(item.uri, queue.activeSourceId)
            }

            val expected =
                listOf(
                    "autoplay spotify:playlist:mfy",
                    "playlistTracks spotify:playlist:mfy",
                    "autoplay spotify:playlist:p1",
                    "playlistTracks spotify:playlist:p1",
                    "autoplay spotify:album:a1",
                    "albumTracks spotify:album:a1",
                    "autoplay spotify:artist:k1",
                    "artistTopTracks spotify:artist:k1",
                )
            assertEquals(expected, spotify.calls)
            assertEquals(List(4) { Screen.PLAYER.name }, subject.moves)
        }

    @Test
    fun `SPOT-11 SRC-12 Spotify's search goes through its own pick, never Yandex's`() = runTest {
        val subject = Subject(this)
        loggedIn()
        subject.model.submitSearch()
        subject.model.setSearchText("   ")
        subject.model.submitSearch()
        runCurrent()
        assertEquals(emptyList<String>(), spotify.calls)

        subject.model.setSearchText("  кино ")
        subject.model.submitSearch()
        runCurrent()

        assertEquals(listOf("searchPick кино"), spotify.calls)
        assertEquals(emptyList<String>(), subject.harness.source.calls)
        assertEquals(
            listOf(
                QueueEvent.SearchStarted("queue_search_title(кино)"),
                QueueEvent.SourceLoaded("Кино", 1),
            ),
            subject.harness.events,
        )
        assertEquals("", subject.model.ui.value.searchText)
        assertEquals(listOf(Screen.PLAYER.name), subject.moves)
    }

    @Test
    fun `SPOT-12 Radio plays the track's station under its title with a source of its own`() = runTest {
        val subject = Subject(this)
        val track = spotify("6PCoZdjSPbRMehHviTrpB3").copy(title = "Кукушка")

        subject.model.playRadio(track)
        runCurrent()

        assertEquals(listOf("station spotify:track:6PCoZdjSPbRMehHviTrpB3"), spotify.calls)
        val queue = subject.harness.controller.state.value
        assertEquals("spotify_radio_title(Кукушка)", queue.title)
        assertEquals("spotify:radio:6PCoZdjSPbRMehHviTrpB3", queue.activeSourceId)
        assertTrue(queue.isWave)
        assertEquals(listOf("r1", "r2"), queue.tracks.map(Track::id))
        assertEquals(QueueEvent.SourceLoading("spotify_radio_title(Кукушка)"), subject.harness.events.first())
        assertEquals(emptyList<String>(), subject.moves)
    }

    private fun TestScope.loggedIn() {
        spotify.saved = true
        spotify.username = "me"
        spotify.state.value = SpotifyState.Online("me")
        runCurrent()
    }

    private suspend fun <T> waitForever(what: String): T = try {
        awaitCancellation()
    } finally {
        spotify.calls += "$what cancelled"
    }

    private inner class Subject(scope: TestScope) {
        val harness = QueueHarness(scope)
        val moves = ArrayList<String>()
        val notices = ArrayList<String>()
        val pages = ArrayList<String>()
        val model =
            SpotifyViewModel(
                spotify = spotify,
                queue = harness.controller,
                strings = ::text,
                io = StandardTestDispatcher(scope.testScheduler),
            )

        init {
            val now = UnconfinedTestDispatcher(scope.testScheduler)
            scope.backgroundScope.launch(now) { model.moves.collect { moves += it.name } }
            scope.backgroundScope.launch(now) { model.notices.collect { notices += it } }
            scope.backgroundScope.launch(now) { model.browserPages.collect { pages += it } }
            scope.runCurrent()
        }
    }

    private class FakeSpotify : SpotifyGateway {
        val calls = ArrayList<String>()
        var saved = false
        override val state = MutableStateFlow<SpotifyState>(SpotifyState.LoggedOut)
        override var username = ""
        override val likedSongsUri: String get() = "spotify:user:$username:collection"
        var onToken: suspend (PairLogin.Code) -> String = { CompletableDeferred<String>().await() }
        var onBrowser: suspend (BrowserLogin.Pages, (String) -> Unit) -> String = { _, _ ->
            CompletableDeferred<String>().await()
        }
        var onPlaylists: suspend () -> List<SpotifyItem> = { listOf(PLAYLIST) }
        var onArtists: suspend () -> List<SpotifyItem> = { emptyList() }

        override fun hasSavedLogin(): Boolean = saved

        override fun login(accessToken: String) {
            calls += "login $accessToken"
        }

        override fun logout() {
            calls += "logout"
            saved = false
            state.value = SpotifyState.LoggedOut
        }

        override suspend fun requestCode(): PairLogin.Code {
            calls += "code"
            return PairLogin.Code("device-1", "ABCD-EFGH", PAIR_URL, intervalMs = 5_000, deadlineMs = 600_000)
        }

        override suspend fun waitForToken(code: PairLogin.Code): String = onToken(code)

        override suspend fun loginInBrowser(pages: BrowserLogin.Pages, openPage: (String) -> Unit): String =
            onBrowser(pages, openPage)

        override suspend fun likedTracks(): List<Track> =
            called("likedTracks") { listOf(spotify("l1"), spotify("l2")) }

        override suspend fun forYou(): List<SpotifyItem> = called("forYou") { listOf(FOR_YOU) }

        override suspend fun playlists(): List<SpotifyItem> = called("playlists") { onPlaylists() }

        override suspend fun savedAlbums(): List<SpotifyItem> = called("savedAlbums") { emptyList() }

        override suspend fun followedArtists(): List<SpotifyItem> = called("followedArtists") { onArtists() }

        override suspend fun playlistTracks(uri: String): List<Track> =
            called("playlistTracks $uri") { listOf(spotify("p1")) }

        override suspend fun albumTracks(uri: String): List<Track> =
            called("albumTracks $uri") { listOf(spotify("a1")) }

        override suspend fun artistTopTracks(uri: String): List<Track> =
            called("artistTopTracks $uri") { listOf(spotify("k1")) }

        override fun autoplay(contextUri: String): Continuation {
            calls += "autoplay $contextUri"
            return FakeContinuation(ContinuationKey("spotify-autoplay", contextUri))
        }

        override fun station(trackUri: String): Continuation {
            calls += "station $trackUri"
            return FakeContinuation(ContinuationKey("spotify-station", trackUri)).apply {
                replies(listOf(spotify("r1"), spotify("r2")))
            }
        }

        override suspend fun searchPick(text: String): SearchPick =
            called("searchPick $text") { SearchPick(listOf(spotify("s1")), "Кино") }

        override fun loggedIn(way: SpotifyLoginWay) {
            calls += "telemetry $way"
        }

        override fun loggedOut() {
            calls += "telemetry logout"
        }

        private suspend fun <T> called(call: String, answer: suspend () -> T): T {
            calls += call
            return answer()
        }
    }

    private companion object {
        const val PAIR_URL = "https://spotify.com/pair?code=ABCDEFGH"
        val FOR_YOU = SpotifyItem("spotify:playlist:mfy", "Discover Weekly")
        val PLAYLIST = SpotifyItem("spotify:playlist:p1", "Дорога")
        val NAMES: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

        fun spotify(id: String) = Track(id, "T$id", source = TrackSource.SPOTIFY)

        fun text(id: Int, args: List<Any>): String {
            val name = NAMES.getValue(id)
            return if (args.isEmpty()) name else "$name(${args.joinToString()})"
        }
    }
}
