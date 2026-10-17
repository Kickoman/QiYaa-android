package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.data.AccentTheme
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.data.AudioQuality
import io.github.kickoman.qiyaa.data.VisualizerMode
import io.github.kickoman.qiyaa.playback.AudioFailure
import io.github.kickoman.qiyaa.playback.JamHostEvent
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.telemetry.ErrorArea
import io.github.kickoman.qiyaa.telemetry.EventCodec
import io.github.kickoman.qiyaa.telemetry.ExitInfo
import io.github.kickoman.qiyaa.telemetry.ExitReason
import io.github.kickoman.qiyaa.telemetry.StartFields
import io.github.kickoman.qiyaa.yandex.RequestFailure

data class ErrorCode(val area: ErrorArea, val kind: String, val httpStatus: Int? = null)

data class ProcessExit(val summary: String?, val reason: Int, val status: Int)

object TelemetryCodes {
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val ON = "on"
    const val OFF = "off"
    const val RELEASE = "release"

    val LOGIN_FAILED = ErrorCode(ErrorArea.LOGIN, "failed")

    private val ARCHES = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86" to "i386")

    fun error(kind: FailureKind): ErrorCode = ErrorCode(
        ErrorArea.PLAYBACK,
        when (kind) {
            FailureKind.NETWORK -> "network"
            FailureKind.SESSION -> "auth"
            FailureKind.TRACK -> "track"
        },
    )

    fun error(failure: AudioFailure): ErrorCode = ErrorCode(
        ErrorArea.AUDIO,
        when (failure) {
            AudioFailure.INIT -> "init"
            AudioFailure.DEVICE -> "device"
            AudioFailure.UNDECODABLE -> "undecodable"
        },
    )

    fun error(failure: RequestFailure): ErrorCode = when (failure.kind) {
        RequestFailure.Kind.NETWORK -> ErrorCode(ErrorArea.API, "network")
        RequestFailure.Kind.HTTP -> ErrorCode(ErrorArea.API, "http", failure.status)
        RequestFailure.Kind.CONTENT -> ErrorCode(ErrorArea.API, "content")
    }

    fun error(event: JamHostEvent): ErrorCode? = when (event) {
        is JamHostEvent.Refused -> ErrorCode(ErrorArea.JAM, EventCodec.kind(event.reason))

        is JamHostEvent.Ended ->
            when (event.why) {
                JamHostEvent.Ended.Why.GONE -> "gone"
                JamHostEvent.Ended.Why.EXPIRED -> "expired"
                JamHostEvent.Ended.Why.BY_SERVER -> "ended-by-server"
                JamHostEvent.Ended.Why.BY_HOST -> null
            }?.let { ErrorCode(ErrorArea.JAM, it) }

        JamHostEvent.NotConnected -> null
    }

    fun onOff(on: Boolean): String = if (on) ON else OFF

    fun language(language: AppLanguage): StartFields.Language = when (language) {
        AppLanguage.BELARUSIAN -> StartFields.Language.BE
        AppLanguage.RUSSIAN -> StartFields.Language.RU
        AppLanguage.ENGLISH -> StartFields.Language.EN
    }

    fun theme(theme: AccentTheme): StartFields.Theme = when (theme) {
        AccentTheme.CLASSIC_GREEN -> StartFields.Theme.GREEN
        AccentTheme.AMBER -> StartFields.Theme.AMBER
        AccentTheme.ICE_BLUE -> StartFields.Theme.ICE
    }

    fun vis(mode: VisualizerMode): StartFields.Vis = when (mode) {
        VisualizerMode.SPECTRUM -> StartFields.Vis.SPECTRUM
        VisualizerMode.SCOPE -> StartFields.Vis.OSCILLOSCOPE
        VisualizerMode.OFF -> StartFields.Vis.OFF
    }

    fun quality(quality: AudioQuality): StartFields.Quality = when (quality) {
        AudioQuality.STANDARD -> StartFields.Quality.STANDARD
        AudioQuality.ECONOMY -> StartFields.Quality.ECONOMY
    }

    fun packaging(buildType: String): StartFields.Packaging =
        if (buildType == RELEASE) StartFields.Packaging.APK else StartFields.Packaging.DEBUG

    fun arch(abi: String): String = ARCHES[abi] ?: abi

    fun locale(language: String, country: String): String = when {
        language.isEmpty() -> EventCodec.NO_LOCALE
        country.isEmpty() -> language
        else -> "${language}_$country"
    }

    fun device(manufacturer: String, model: String): String {
        val maker = manufacturer.trim()
        val name = model.trim()
        return if (maker.isEmpty() || name.startsWith(maker, ignoreCase = true)) name else "$maker $name"
    }

    fun exitInfo(reason: Int, status: Int): ExitInfo = when (reason) {
        REASON_CRASH -> ExitInfo(ExitReason.CRASH)
        REASON_CRASH_NATIVE -> ExitInfo(ExitReason.NATIVE_CRASH, status)
        REASON_ANR -> ExitInfo(ExitReason.ANR)
        else -> ExitInfo(ExitReason.OTHER)
    }

    fun exits(newestFirst: List<ProcessExit>): Map<String, ExitInfo> {
        val exits = LinkedHashMap<String, ExitInfo>()
        for (exit in newestFirst) {
            val session = exit.summary?.takeIf(EventCodec.SESSION::matches) ?: continue
            exits.putIfAbsent(session, exitInfo(exit.reason, exit.status))
        }
        return exits
    }
}
