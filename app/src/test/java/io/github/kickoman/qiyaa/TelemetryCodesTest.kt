package io.github.kickoman.qiyaa

import android.app.ApplicationExitInfo
import io.github.kickoman.qiyaa.data.AccentTheme
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.data.AudioQuality
import io.github.kickoman.qiyaa.data.VisualizerMode
import io.github.kickoman.qiyaa.lyrics.LyricsShown
import io.github.kickoman.qiyaa.playback.AudioFailure
import io.github.kickoman.qiyaa.playback.JamHostEvent
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.spotify.SpotifyProblem
import io.github.kickoman.qiyaa.telemetry.ErrorArea
import io.github.kickoman.qiyaa.telemetry.EventCodec
import io.github.kickoman.qiyaa.telemetry.ExitInfo
import io.github.kickoman.qiyaa.telemetry.ExitReason
import io.github.kickoman.qiyaa.telemetry.FeatureName
import io.github.kickoman.qiyaa.telemetry.LyricsOutcome
import io.github.kickoman.qiyaa.telemetry.StartFields
import io.github.kickoman.qiyaa.yandex.RequestFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryCodesTest {
    @Test
    fun `playback failures are playback network, auth and track`() {
        assertEquals(ErrorCode(ErrorArea.PLAYBACK, "network"), TelemetryCodes.error(FailureKind.NETWORK))
        assertEquals(ErrorCode(ErrorArea.PLAYBACK, "auth"), TelemetryCodes.error(FailureKind.SESSION))
        assertEquals(ErrorCode(ErrorArea.PLAYBACK, "track"), TelemetryCodes.error(FailureKind.TRACK))
    }

    @Test
    fun `audio failures are audio init, device and undecodable`() {
        assertEquals(ErrorCode(ErrorArea.AUDIO, "init"), TelemetryCodes.error(AudioFailure.INIT))
        assertEquals(ErrorCode(ErrorArea.AUDIO, "device"), TelemetryCodes.error(AudioFailure.DEVICE))
        assertEquals(
            ErrorCode(ErrorArea.AUDIO, "undecodable"),
            TelemetryCodes.error(AudioFailure.UNDECODABLE),
        )
    }

    @Test
    fun `request failures are api network, http with the status and content`() {
        assertEquals(
            ErrorCode(ErrorArea.API, "network"),
            TelemetryCodes.error(RequestFailure(RequestFailure.Kind.NETWORK)),
        )
        assertEquals(
            ErrorCode(ErrorArea.API, "http", 403),
            TelemetryCodes.error(RequestFailure(RequestFailure.Kind.HTTP, 403)),
        )
        assertEquals(
            ErrorCode(ErrorArea.API, "content"),
            TelemetryCodes.error(RequestFailure(RequestFailure.Kind.CONTENT)),
        )
    }

    @Test
    fun `a failed sign-in is login failed`() {
        assertEquals(ErrorCode(ErrorArea.LOGIN, "failed"), TelemetryCodes.LOGIN_FAILED)
    }

    @Test
    fun `a jam refusal is its reason, and a reason that is not a code is other`() {
        assertEquals(
            ErrorCode(ErrorArea.JAM, "server-full"),
            TelemetryCodes.error(JamHostEvent.Refused("server-full")),
        )
        assertEquals(
            ErrorCode(ErrorArea.JAM, "rate-limited"),
            TelemetryCodes.error(JamHostEvent.Refused("rate-limited")),
        )
        assertEquals(
            ErrorCode(ErrorArea.JAM, "other"),
            TelemetryCodes.error(JamHostEvent.Refused("Room 7k3m9q2x full")),
        )
        assertEquals(ErrorCode(ErrorArea.JAM, "other"), TelemetryCodes.error(JamHostEvent.Refused("")))
    }

    @Test
    fun `a jam that ends by itself is gone, expired or ended-by-server, and the host's end is nothing`() {
        assertEquals(
            ErrorCode(ErrorArea.JAM, "gone"),
            TelemetryCodes.error(ended(JamHostEvent.Ended.Why.GONE)),
        )
        assertEquals(
            ErrorCode(ErrorArea.JAM, "expired"),
            TelemetryCodes.error(ended(JamHostEvent.Ended.Why.EXPIRED)),
        )
        assertEquals(
            ErrorCode(ErrorArea.JAM, "ended-by-server"),
            TelemetryCodes.error(ended(JamHostEvent.Ended.Why.BY_SERVER)),
        )
        assertNull(TelemetryCodes.error(ended(JamHostEvent.Ended.Why.BY_HOST)))
    }

    @Test
    fun `a removal the host makes without a connection is not a jam error`() {
        assertNull(TelemetryCodes.error(JamHostEvent.NotConnected))
    }

    @Test
    fun `TEL-18 Spotify's problems are spotify errors, http with its status`() {
        val codes =
            listOf(
                SpotifyProblem.LoginRefused(12),
                SpotifyProblem.LoginRefused(11),
                SpotifyProblem.KeyRefused,
                SpotifyProblem.KeyFailed,
                SpotifyProblem.AccessPoint,
                SpotifyProblem.Http(403),
                SpotifyProblem.Content,
                SpotifyProblem.QueryOutdated,
            ).map(TelemetryCodes::error)
        val expected =
            listOf(
                ErrorCode(ErrorArea.SPOTIFY, "login-expired"),
                ErrorCode(ErrorArea.SPOTIFY, "premium-required"),
                ErrorCode(ErrorArea.SPOTIFY, "key-refused"),
                ErrorCode(ErrorArea.SPOTIFY, "key-failed"),
                ErrorCode(ErrorArea.SPOTIFY, "access-point"),
                ErrorCode(ErrorArea.SPOTIFY, "http", 403),
                ErrorCode(ErrorArea.SPOTIFY, "content"),
                ErrorCode(ErrorArea.SPOTIFY, "query-outdated"),
            )
        assertEquals(expected, codes)
    }

    @Test
    fun `TEL-18 another refusal of the login sends nothing, as on the desktop`() {
        assertNull(TelemetryCodes.error(SpotifyProblem.LoginRefused(9)))
        assertNull(TelemetryCodes.error(SpotifyProblem.LoginRefused(0)))
    }

    @Test
    fun `TEL-18 a Spotify login says its way, a logout says logout`() {
        assertEquals("login-code", TelemetryCodes.spotifyLogin(SpotifyLoginWay.CODE))
        assertEquals("login-browser", TelemetryCodes.spotifyLogin(SpotifyLoginWay.BROWSER))
        assertEquals("logout", TelemetryCodes.SPOTIFY_LOGOUT)
        assertEquals("spotify", FeatureName.SPOTIFY.code)
        assertEquals("spotify", ErrorArea.SPOTIFY.code)
    }

    @Test
    fun `features say on and off, the language code and the visualization`() {
        assertEquals("on", TelemetryCodes.onOff(true))
        assertEquals("off", TelemetryCodes.onOff(false))
        assertEquals(
            listOf("be", "ru", "en"),
            listOf(AppLanguage.BELARUSIAN, AppLanguage.RUSSIAN, AppLanguage.ENGLISH).map {
                TelemetryCodes.language(it).code
            },
        )
        assertEquals(
            listOf("spectrum", "oscilloscope", "off"),
            listOf(VisualizerMode.SPECTRUM, VisualizerMode.SCOPE, VisualizerMode.OFF).map {
                TelemetryCodes.vis(it).code
            },
        )
    }

    @Test
    fun `the start's theme, quality and package come from the settings and the build type`() {
        assertEquals(
            listOf("green", "amber", "ice"),
            listOf(AccentTheme.CLASSIC_GREEN, AccentTheme.AMBER, AccentTheme.ICE_BLUE).map {
                TelemetryCodes.theme(it).code
            },
        )
        assertEquals(StartFields.Quality.STANDARD, TelemetryCodes.quality(AudioQuality.STANDARD))
        assertEquals(StartFields.Quality.ECONOMY, TelemetryCodes.quality(AudioQuality.ECONOMY))
        assertEquals(StartFields.Packaging.APK, TelemetryCodes.packaging("release"))
        assertEquals(StartFields.Packaging.DEBUG, TelemetryCodes.packaging("debug"))
    }

    @Test
    fun `ABIs are named as on the desktop and the rest is left to the codec`() {
        assertEquals("arm64", TelemetryCodes.arch("arm64-v8a"))
        assertEquals("arm", TelemetryCodes.arch("armeabi-v7a"))
        assertEquals("i386", TelemetryCodes.arch("x86"))
        assertEquals("x86_64", TelemetryCodes.arch("x86_64"))
        assertEquals("riscv64", EventCodec.arch(TelemetryCodes.arch("riscv64")))
    }

    @Test
    fun `the locale is language and country of the system, and C without a language`() {
        assertEquals("be_BY", TelemetryCodes.locale("be", "BY"))
        assertEquals("es_419", TelemetryCodes.locale("es", "419"))
        assertEquals("sr_RS", TelemetryCodes.locale("sr", "RS"))
        assertEquals("en", TelemetryCodes.locale("en", ""))
        assertEquals("C", TelemetryCodes.locale("", ""))
        val valid = listOf("be_BY", "es_419", "sr_RS", "en", "C")
        for (locale in valid) assertEquals(locale, EventCodec.locale(locale))
    }

    @Test
    fun `the device is maker and model, without the maker when the model already names it`() {
        assertEquals("Google Pixel 7", TelemetryCodes.device("Google", "Pixel 7"))
        assertEquals("samsung SM-S911B", TelemetryCodes.device("samsung", "SM-S911B"))
        assertEquals("Xiaomi 13", TelemetryCodes.device("Xiaomi", "Xiaomi 13"))
        assertEquals("OnePlus CPH2449", TelemetryCodes.device(" OnePlus ", "CPH2449 "))
        assertEquals("motorola edge 40", TelemetryCodes.device("Motorola", "motorola edge 40"))
        assertEquals("Pixel 7", TelemetryCodes.device("", "Pixel 7"))
    }

    @Test
    fun `TEL-16 the exit reason codes are Android's own`() {
        assertEquals(ApplicationExitInfo.REASON_CRASH, TelemetryCodes.REASON_CRASH)
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, TelemetryCodes.REASON_CRASH_NATIVE)
        assertEquals(ApplicationExitInfo.REASON_ANR, TelemetryCodes.REASON_ANR)
    }

    @Test
    fun `TEL-16 a Java crash, a native crash with its signal, an ANR, and every other end`() {
        assertEquals(ExitInfo(ExitReason.CRASH), TelemetryCodes.exitInfo(ApplicationExitInfo.REASON_CRASH, 0))
        assertEquals(
            ExitInfo(ExitReason.NATIVE_CRASH, 11),
            TelemetryCodes.exitInfo(ApplicationExitInfo.REASON_CRASH_NATIVE, 11),
        )
        assertEquals(ExitInfo(ExitReason.ANR), TelemetryCodes.exitInfo(ApplicationExitInfo.REASON_ANR, 0))
        val others =
            listOf(
                ApplicationExitInfo.REASON_UNKNOWN,
                ApplicationExitInfo.REASON_EXIT_SELF,
                ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_USER_STOPPED,
                ApplicationExitInfo.REASON_OTHER,
            )
        for (reason in others) {
            assertEquals(
                "$reason",
                ExitInfo(ExitReason.OTHER),
                TelemetryCodes.exitInfo(reason, 9),
            )
        }
    }

    @Test
    fun `TEL-16 exits are keyed by the session in the summary, the newest of each, others skipped`() {
        val first = "0d6e4c8a-6a3b-4f1e-9a57-3c2b1d0e9f84"
        val second = "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53"
        val exits =
            TelemetryCodes.exits(
                listOf(
                    ProcessExit(first, ApplicationExitInfo.REASON_ANR, 0),
                    ProcessExit(null, ApplicationExitInfo.REASON_CRASH, 0),
                    ProcessExit("not a session", ApplicationExitInfo.REASON_CRASH, 0),
                    ProcessExit(second, ApplicationExitInfo.REASON_CRASH_NATIVE, 6),
                    ProcessExit(first, ApplicationExitInfo.REASON_USER_REQUESTED, 0),
                ),
            )
        assertEquals(
            mapOf(first to ExitInfo(ExitReason.ANR), second to ExitInfo(ExitReason.NATIVE_CRASH, 6)),
            exits,
        )
    }

    private fun ended(why: JamHostEvent.Ended.Why) = JamHostEvent.Ended(why)

    @Test
    fun `TEL-17 every lyrics outcome of the finder has its count`() {
        val outcomes = LyricsShown.entries.map(TelemetryCodes::lyricsOutcome)
        assertEquals(LyricsOutcome.entries.toList(), outcomes)
    }
}
