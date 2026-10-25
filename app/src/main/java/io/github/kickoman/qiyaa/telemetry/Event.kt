package io.github.kickoman.qiyaa.telemetry

import java.time.Instant

sealed interface Event {
    val at: Instant
    val session: String
    val version: String

    data class Start(
        override val at: Instant,
        override val session: String,
        override val version: String,
        val fields: StartFields,
    ) : Event

    data class Exit(
        override val at: Instant,
        override val session: String,
        override val version: String,
        val counts: Counts,
    ) : Event

    data class Crash(
        override val at: Instant,
        override val session: String,
        override val version: String,
        val signal: String,
        val frames: List<String> = emptyList(),
        val seconds: Long = 0,
        val exceptionType: String? = null,
    ) : Event

    data class UncleanExit(
        override val at: Instant,
        override val session: String,
        override val version: String,
        val seconds: Long,
    ) : Event

    data class Error(
        override val at: Instant,
        override val session: String,
        override val version: String,
        val area: ErrorArea,
        val kind: String,
        val httpStatus: Int? = null,
    ) : Event

    data class Feature(
        override val at: Instant,
        override val session: String,
        override val version: String,
        val name: FeatureName,
        val value: String,
    ) : Event
}

enum class ErrorArea(val code: String) {
    PLAYBACK("playback"),
    AUDIO("audio"),
    API("api"),
    LOGIN("login"),
    JAM("jam"),
    SPOTIFY("spotify"),
}

enum class FeatureName(val code: String) {
    JAM("jam"),
    LANGUAGE("language"),
    EQUALIZER("equalizer"),
    VIS("vis"),
    LYRICS("lyrics"),
    LYRICS_SERVICES("lyrics-services"),
    SPOTIFY("spotify"),
}

data class StartFields(
    val osVersion: String = "",
    val sdk: Int = 1,
    val device: String = "",
    val kernel: String = "",
    val arch: String = "",
    val packaging: Packaging = Packaging.DEBUG,
    val locale: String = EventCodec.NO_LOCALE,
    val language: Language = Language.EN,
    val dpr: Double = 1.0,
    val theme: Theme = Theme.GREEN,
    val vis: Vis = Vis.SPECTRUM,
    val equalizer: Boolean = false,
    val quality: Quality = Quality.STANDARD,
    val qualityMetered: Quality = Quality.STANDARD,
    val loggedIn: Boolean = false,
    val spotifyLoggedIn: Boolean? = null,
    val firstRun: Boolean = false,
) {
    enum class Packaging(val code: String) { APK("apk"), DEBUG("debug") }

    enum class Language(val code: String) { BE("be"), RU("ru"), EN("en") }

    enum class Theme(val code: String) { GREEN("green"), AMBER("amber"), ICE("ice") }

    enum class Vis(val code: String) { SPECTRUM("spectrum"), OSCILLOSCOPE("oscilloscope"), OFF("off") }

    enum class Quality(val code: String) { STANDARD("standard"), ECONOMY("economy") }
}

data class Counts(
    val seconds: Long = 0,
    val tracks: Long = 0,
    val playMinutes: Long = 0,
    val jams: Long = 0,
    val errorsDropped: Long = 0,
    val lyrics: LyricsCounts = LyricsCounts(),
)

enum class LyricsOutcome {
    SOURCE_SYNCED,
    THIRD_PARTY_SYNCED,
    SOURCE_TEXT,
    THIRD_PARTY_TEXT,
    INSTRUMENTAL,
    NONE,
}

data class LyricsCounts(
    val sourceSynced: Long = 0,
    val thirdPartySynced: Long = 0,
    val sourceText: Long = 0,
    val thirdPartyText: Long = 0,
    val instrumental: Long = 0,
    val none: Long = 0,
    val thirdPartyFailed: Long = 0,
) {
    val lookups: Long get() = sourceSynced + thirdPartySynced + sourceText + thirdPartyText + instrumental +
        none

    fun counted(outcome: LyricsOutcome, failed: Boolean): LyricsCounts {
        val shown =
            when (outcome) {
                LyricsOutcome.SOURCE_SYNCED -> copy(sourceSynced = sourceSynced + 1)
                LyricsOutcome.THIRD_PARTY_SYNCED -> copy(thirdPartySynced = thirdPartySynced + 1)
                LyricsOutcome.SOURCE_TEXT -> copy(sourceText = sourceText + 1)
                LyricsOutcome.THIRD_PARTY_TEXT -> copy(thirdPartyText = thirdPartyText + 1)
                LyricsOutcome.INSTRUMENTAL -> copy(instrumental = instrumental + 1)
                LyricsOutcome.NONE -> copy(none = none + 1)
            }
        return if (failed) shown.copy(thirdPartyFailed = thirdPartyFailed + 1) else shown
    }
}
