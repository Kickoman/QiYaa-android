package io.github.kickoman.qiyaa

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.data.AudioQuality
import io.github.kickoman.qiyaa.data.JamFile
import io.github.kickoman.qiyaa.data.QueueFile
import io.github.kickoman.qiyaa.data.Settings
import io.github.kickoman.qiyaa.data.ShuffleChoice
import io.github.kickoman.qiyaa.data.SpotifyStore
import io.github.kickoman.qiyaa.data.TokenStore
import io.github.kickoman.qiyaa.jam.JamGuest
import io.github.kickoman.qiyaa.jam.JamGuestStore
import io.github.kickoman.qiyaa.jam.JamSessionStore
import io.github.kickoman.qiyaa.jam.JamStore
import io.github.kickoman.qiyaa.local.LocalCheck
import io.github.kickoman.qiyaa.local.LocalLibrary
import io.github.kickoman.qiyaa.local.LocalLyrics
import io.github.kickoman.qiyaa.lyrics.Lrclib
import io.github.kickoman.qiyaa.lyrics.LyricsFinder
import io.github.kickoman.qiyaa.playback.JamCatalog
import io.github.kickoman.qiyaa.playback.JamHost
import io.github.kickoman.qiyaa.playback.JamHostConfig
import io.github.kickoman.qiyaa.playback.OkHttpJamTransport
import io.github.kickoman.qiyaa.playback.PlaybackCache
import io.github.kickoman.qiyaa.playback.ReadInterrupts
import io.github.kickoman.qiyaa.playback.SpotifyBitrates
import io.github.kickoman.qiyaa.playback.TrackUrlCache
import io.github.kickoman.qiyaa.queue.LibraryMusicSource
import io.github.kickoman.qiyaa.queue.QueueActivity
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.QueueStore
import io.github.kickoman.qiyaa.queue.SearchPick
import io.github.kickoman.qiyaa.queue.ShuffleAlgorithm
import io.github.kickoman.qiyaa.spotify.BrowserLogin
import io.github.kickoman.qiyaa.spotify.PairLogin
import io.github.kickoman.qiyaa.spotify.SpotifyAccount
import io.github.kickoman.qiyaa.spotify.SpotifyFiles
import io.github.kickoman.qiyaa.spotify.SpotifyIdentity
import io.github.kickoman.qiyaa.spotify.SpotifyLibrary
import io.github.kickoman.qiyaa.spotify.SpotifySession
import io.github.kickoman.qiyaa.spotify.SpotifyWeb
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.DeviceAuth
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.Quality
import io.github.kickoman.qiyaa.yandex.Session
import io.github.kickoman.qiyaa.yandex.YandexApi
import io.github.kickoman.qiyaa.yandex.signedIn
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class AppGraph(val context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    val settings = Settings(context, defaultJamServer = BuildConfig.JAM_URL)
    val screen = ScreenPresence(context.applicationContext as Application)
    val telemetry: AppTelemetry? =
        if (BuildConfig.TELEMETRY && BuildConfig.TELEMETRY_URL.startsWith("https://")) {
            AppTelemetry(
                application = context.applicationContext as Application,
                settings = settings,
                httpClient = httpClient,
                endpoint = BuildConfig.TELEMETRY_URL,
                scope = applicationScope,
                onScreen = screen.onScreen,
            ).also { it.start() }
        } else {
            null
        }
    val tokenStore = TokenStore(context)
    val api =
        YandexApi(httpClient, log = { message -> Log.i(LOG_TAG, message) }).also {
            it.token =
                tokenStore.load()
        }
    val library = Library(api)
    val networkMonitor = NetworkMonitor(context, applicationScope)
    val session = Session(library, networkMonitor.available, applicationScope).also { it.start() }
    val deviceAuth = DeviceAuth(httpClient, deviceName = Build.MODEL ?: "Android")
    val audioBus =
        AudioBus().also { bus ->
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.eq.collect(bus.equalizer::publish)
            }
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.balance.collect(bus::setBalance)
            }
        }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val spotifyStore = SpotifyStore(context)
    private val spotifyIdentity =
        SpotifyIdentity(
            appVersion = BuildConfig.VERSION_NAME,
            deviceId = spotifyStore.deviceId { SpotifyIdentity.newDeviceId() },
            osRelease = System.getProperty("os.version").orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
    private val queuePlaying = MutableStateFlow(false)
    val spotifySession =
        SpotifySession(
            client = httpClient,
            identity = spotifyIdentity,
            network = networkMonitor.available,
            inUse = combine(screen.onScreen, queuePlaying) { onScreen, playing -> onScreen || playing },
            scope = applicationScope,
            log = ::logSpotify,
        )
    val spotifyWeb = SpotifyWeb(httpClient, spotifySession, spotifyIdentity, log = ::logSpotify)
    val spotifyLibrary = SpotifyLibrary(spotifyWeb, spotifySession)
    val spotifyAccount =
        SpotifyAccount(spotifySession, SpotifySavedCredentials(spotifyStore)).also { it.start() }
    val spotifyPairLogin = PairLogin(httpClient)
    val spotifyBrowserLogin = BrowserLogin(httpClient)
    val spotifyContinuations = SpotifyContinuations(spotifyLibrary.asCatalog())
    val spotifySearch: suspend (String) -> SearchPick = spotifyContinuations::searchPick

    val queue =
        QueueController(
            source = LibraryMusicSource(library),
            connectivity = networkMonitor.available,
            scope = mainScope,
            io = Dispatchers.IO,
            store = QueueFile(context).asQueueStore(),
            logFailure = { stage, failed -> Log.w(LOG_TAG, "Queue $stage failed", failed) },
            continueWithVibeByDefault = { settings.continueWithVibe.value },
            errorKindOf = ::errorKindOf,
            continuationOf = spotifyContinuations::of,
        ).also { controller ->
            mainScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.shuffleAlgorithm.collect { controller.setShuffleAlgorithm(algorithmOf(it)) }
            }
            mainScope.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.activity.collect { activity ->
                    if (activity is QueueActivity.PlayingChanged) queuePlaying.value = activity.playing
                }
            }
        }

    val localLibrary = LocalLibrary(context)
    private var localQueueChecked = false

    init {
        checkRestoredLocalQueue()
    }

    fun checkRestoredLocalQueue() {
        mainScope.launch { checkLocalQueue() }
    }

    private suspend fun checkLocalQueue() {
        if (localQueueChecked || !localLibrary.hasPermission()) return
        localQueueChecked = true
        val saved = queue.state.value.tracks
        val ids = LocalCheck.localIds(saved)
        if (ids.isEmpty()) return
        val found =
            try {
                withContext(Dispatchers.IO) { localLibrary.rowsById(ids) }
            } catch (failed: Exception) {
                Log.w(LOG_TAG, "Checking the restored local queue failed", failed)
                return
            }
        if (LocalCheck.renumbered(saved, found) && queue.clearIfRestored()) {
            Log.i(LOG_TAG, "The media library was renumbered: the restored local queue is cleared (LOCAL-14)")
        }
    }

    val qualityInEffect: StateFlow<Quality> =
        combine(
            settings.quality,
            settings.qualityMetered,
            networkMonitor.metered,
        ) { unmetered, metered, onMetered -> qualityFor(unmetered, metered, onMetered) }
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                qualityFor(
                    settings.quality.value,
                    settings.qualityMetered.value,
                    networkMonitor.metered.value,
                ),
            )

    /** Signed track links, shared by the player and the jam host's listeners (LISTEN-02). */
    val trackLinks =
        TrackUrlCache(
            clock = SystemClock::elapsedRealtime,
            quality = { qualityInEffect.value },
        ) { id, quality ->
            Log.d(LOG_TAG, "Signing the link of track $id ($quality)")
            api.resolveTrackUrl(id, quality)
        }

    val spotifyFiles =
        SpotifyFiles(
            web = spotifyWeb,
            session = spotifySession,
            bitrates = { SpotifyBitrates.of(qualityInEffect.value) },
            clock = SystemClock::elapsedRealtime,
            log = ::logSpotify,
        )

    val readInterrupts =
        ReadInterrupts(
            schedule = daemonScheduler("QiYaa:ReadInterrupts"),
            log = { message -> Log.d(LOG_TAG, message) },
        )

    val playbackCache =
        PlaybackCache(
            folder = { File(context.cacheDir, PlaybackCache.FOLDER) },
            removals = daemonScheduler("QiYaa:PlaybackCache"),
            log = { message -> Log.d(LOG_TAG, message) },
        )

    val jamHost =
        JamHost(
            transport = OkHttpJamTransport(httpClient),
            connectivity = networkMonitor.available,
            queue = queue,
            catalog = library.asJamCatalog(),
            store = JamSessionStore(JamFile(context).asJamStore()),
            config = {
                JamHostConfig(
                    settings.jamServer.value,
                    settings.jamWaveFeedback.value,
                    settings.jamShareAudio.value,
                )
            },
            signedLink = trackLinks::peek,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            io = Dispatchers.IO,
            appVersion = BuildConfig.VERSION_NAME,
            log = { message, failed -> Log.w(LOG_TAG, message, failed) },
        )

    val jamGuest =
        JamGuest(
            transport = OkHttpJamTransport(httpClient),
            connectivity = networkMonitor.available,
            store = JamGuestStore(JamFile(context, JamFile.GUEST).asJamStore()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            io = Dispatchers.IO,
            appVersion = BuildConfig.VERSION_NAME,
            log = { message, failed -> Log.w(LOG_TAG, message, failed) },
        )

    val lyricsShown = MutableStateFlow(false)
    val lyricsOnScreen = MutableStateFlow(false)
    val lyricsTrack = MutableStateFlow<Track?>(null)

    val lyricsFinder =
        LyricsFinder(
            scope = mainScope,
            source = SourceLyrics(
                YandexLyrics(api::lyricsText) {
                    session.state.value.signedIn
                },
                LocalLyrics(context),
            ),
            thirdParty = Lrclib(httpClient, BuildConfig.VERSION_NAME),
            currentTrack = lyricsTrack,
            active = combine(lyricsShown, lyricsOnScreen) { shown, onScreen -> shown && onScreen },
            thirdPartyEnabled = settings.lyricsThirdParty,
            connectivity = networkMonitor.available,
            signedIn = session.state.map { it.signedIn },
        )

    init {
        telemetry?.connect(
            api,
            session.state,
            queue,
            jamHost,
            networkMonitor.available,
            lyricsShown,
            lyricsFinder.lookups,
            spotifyAccount,
            merge(spotifySession.problems, spotifyWeb.problems),
        )
    }

    companion object {
        const val HTTP_TIMEOUT_SECONDS = 20L
        const val LOG_TAG = "QiYaa"

        private fun qualityFor(unmetered: AudioQuality, metered: AudioQuality, onMetered: Boolean): Quality =
            when (if (onMetered) metered else unmetered) {
                AudioQuality.STANDARD -> Quality.STANDARD
                AudioQuality.ECONOMY -> Quality.ECONOMY
            }

        private fun logSpotify(message: String) {
            Log.i(LOG_TAG, "Spotify: $message")
        }

        private fun algorithmOf(choice: ShuffleChoice): ShuffleAlgorithm = when (choice) {
            ShuffleChoice.WITHOUT_REPEATS -> ShuffleAlgorithm.WITHOUT_REPEATS
            ShuffleChoice.RANDOM_TRACK -> ShuffleAlgorithm.RANDOM_TRACK
        }

        private fun JamFile.asJamStore(): JamStore = object : JamStore {
            override fun read(): String? = this@asJamStore.read()

            override fun write(text: String?) = this@asJamStore.write(text)
        }

        private fun Library.asJamCatalog(): JamCatalog = object : JamCatalog {
            override suspend fun searchTracks(text: String): List<Track> =
                this@asJamCatalog.searchTracks(text)

            override suspend fun tracks(ids: List<String>): List<Track> = tracksByIds(ids)
        }

        private fun QueueFile.asQueueStore(): QueueStore = object : QueueStore {
            override fun read(): String? = this@asQueueStore.read()

            override fun write(text: String) = this@asQueueStore.write(text)
        }

        private fun daemonScheduler(name: String): (Long, () -> Unit) -> Unit {
            val executor = Executors.newSingleThreadScheduledExecutor { task -> daemon(task, name) }
            return { delayMs, task -> executor.schedule(Runnable(task), delayMs, TimeUnit.MILLISECONDS) }
        }

        private fun daemon(task: Runnable, name: String): Thread {
            val thread = Thread(task, name)
            thread.isDaemon = true
            return thread
        }
    }
}
