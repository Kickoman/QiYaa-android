package io.github.kickoman.qiyaa.telemetry

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object EventCodec {
    const val APP = "android"
    const val OS = "android"
    const val UNKNOWN = "unknown"
    const val OTHER = "other"
    const val NO_LOCALE = "C"
    const val UNKNOWN_SIGNAL = "UNKNOWN"
    const val MAX_FRAMES = 32
    const val MAX_COUNT = 100_000_000L
    const val MIN_DPR = 0.5
    const val MAX_DPR = 8.0
    const val MIN_SDK = 1
    const val MAX_SDK = 1_000
    private const val MAX_OS_VERSION = 32
    private const val MAX_KERNEL = 64
    private const val MAX_DEVICE = 64
    private const val MAX_ARCH = 16
    private const val MAX_VERSION = 32
    private const val MAX_FRAME_PLACE = 200
    private const val FIRST_HTTP_STATUS = 100
    private const val LAST_HTTP_STATUS = 599

    val SESSION = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    val VERSION = Regex("^[0-9A-Za-z.+_-]{1,32}$")
    val SIGNAL = Regex("^[A-Z][A-Z0-9_]{0,39}$")
    val FRAME = Regex("^[A-Za-z0-9_$.<>-]{1,200}:[0-9]{1,7}$")
    val EXCEPTION_TYPE = Regex("^[A-Za-z_$][A-Za-z0-9_$.]{0,159}$")
    private val FRAME_LINE = Regex("^[0-9]{1,7}$")
    private val KIND = Regex("^[a-z0-9-]{1,32}$")
    private val FEATURE_VALUE = Regex("^[A-Za-z0-9 ._-]{1,48}$")
    private val LOCALE = Regex("^(C|[a-z]{2,3}(_[A-Za-z0-9]{2,4})?)$")
    private val LANGUAGE = Regex("^[a-z]{2,3}$")
    private val REGION = Regex("^([A-Z]{2}|[0-9]{3})$")

    private val AT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun encode(event: Event): JsonObject? {
        if (!SESSION.matches(event.session)) return null
        if (event is Event.Feature && featureValue(event.value) == null) return null
        return buildJsonObject {
            put("type", typeOf(event))
            put("at", at(event.at))
            put("session", event.session)
            put("version", version(event.version))
            when (event) {
                is Event.Start -> putStart(event.fields)
                is Event.Exit -> putCounts(event.counts)
                is Event.Crash -> putCrash(event)
                is Event.UncleanExit -> put("seconds", count(event.seconds))
                is Event.Error -> {
                    put("area", event.area.code)
                    put("kind", kind(event.kind))
                    httpStatus(event.httpStatus)?.let { put("httpStatus", it) }
                }
                is Event.Feature -> {
                    put("name", event.name.code)
                    put("value", event.value)
                }
            }
        }
    }

    fun batch(machine: String, lines: List<String>): String = buildString {
        append("{\"app\":").append(JsonPrimitive(APP)).append(",\"machine\":").append(JsonPrimitive(machine))
        append(",\"events\":[")
        lines.joinTo(this, ",")
        append("]}")
    }

    fun at(instant: Instant): String = AT.format(instant)

    fun version(raw: String): String {
        val cleaned = raw.map { if (it.isAsciiLetterOrDigit() || it in ".+_-") it else '_' }.joinToString("")
        return cleaned.take(MAX_VERSION).ifEmpty { UNKNOWN }
    }

    fun device(raw: String): String {
        val printable = raw.filter { it in ' '..'~' }.trim()
        return printable.take(MAX_DEVICE).trim().ifEmpty { UNKNOWN }
    }

    fun text(raw: String, max: Int): String = cut(raw.filterNot(::isControl), max).ifEmpty { UNKNOWN }

    fun arch(raw: String): String {
        val cleaned = raw.lowercase(Locale.ROOT).replace('-', '_').filter {
            it == '_' ||
                it.isAsciiLowerOrDigit()
        }
        return cleaned.take(MAX_ARCH).ifEmpty { UNKNOWN }
    }

    fun locale(raw: String): String {
        if (LOCALE.matches(raw)) return raw
        val parts = raw.split('_', '-')
        val language = parts.first().takeIf(LANGUAGE::matches) ?: return NO_LOCALE
        val region = parts.drop(1).firstOrNull(REGION::matches) ?: return language
        return "${language}_$region"
    }

    fun dpr(value: Double): Double = if (value.isFinite()) value.coerceIn(MIN_DPR, MAX_DPR) else 1.0

    fun kind(raw: String): String = raw.takeIf(KIND::matches) ?: OTHER

    fun featureValue(raw: String): String? = raw.takeIf(FEATURE_VALUE::matches)

    fun httpStatus(status: Int?): Int? = status?.takeIf { it in FIRST_HTTP_STATUS..LAST_HTTP_STATUS }

    fun signal(raw: String): String = raw.takeIf(SIGNAL::matches) ?: UNKNOWN_SIGNAL

    fun exceptionType(raw: String?): String? = raw?.takeIf(EXCEPTION_TYPE::matches)

    fun frame(raw: String): String? {
        val colon = raw.lastIndexOf(':')
        val place = if (colon < 0) raw else raw.substring(0, colon)
        val number = if (colon < 0) "0" else raw.substring(colon + 1).takeIf(FRAME_LINE::matches) ?: "0"
        val cleaned = place.map {
            if (it.isAsciiLetterOrDigit() ||
                it in "_$.<>-"
            ) {
                it
            } else {
                '_'
            }
        }.joinToString("")
        return "${cleaned.take(MAX_FRAME_PLACE)}:$number".takeIf(FRAME::matches)
    }

    fun count(value: Long): Long = value.coerceIn(0, MAX_COUNT)

    private fun typeOf(event: Event): String = when (event) {
        is Event.Start -> "start"
        is Event.Exit -> "exit"
        is Event.Crash -> "crash"
        is Event.UncleanExit -> "unclean_exit"
        is Event.Error -> "error"
        is Event.Feature -> "feature"
    }

    private fun JsonObjectBuilder.putStart(fields: StartFields) {
        put("os", OS)
        put("osVersion", text(fields.osVersion, MAX_OS_VERSION))
        put("sdk", fields.sdk.coerceIn(MIN_SDK, MAX_SDK))
        put("device", device(fields.device))
        put("kernel", text(fields.kernel, MAX_KERNEL))
        put("arch", arch(fields.arch))
        put("package", fields.packaging.code)
        put("locale", locale(fields.locale))
        put("language", fields.language.code)
        put("dpr", dpr(fields.dpr))
        put("theme", fields.theme.code)
        put("vis", fields.vis.code)
        put("equalizer", fields.equalizer)
        put("quality", fields.quality.code)
        put("qualityMetered", fields.qualityMetered.code)
        put("loggedIn", fields.loggedIn)
        put("firstRun", fields.firstRun)
    }

    private fun JsonObjectBuilder.putCounts(counts: Counts) {
        put("seconds", count(counts.seconds))
        put("tracks", count(counts.tracks))
        put("playMinutes", count(counts.playMinutes))
        put("jams", count(counts.jams))
        put("errorsDropped", count(counts.errorsDropped))
    }

    private fun JsonObjectBuilder.putCrash(event: Event.Crash) {
        put("signal", signal(event.signal))
        putJsonArray("frames") {
            for (frame in event.frames.mapNotNull(::frame).take(MAX_FRAMES)) add(frame)
        }
        put("seconds", count(event.seconds))
        exceptionType(event.exceptionType)?.let { put("exceptionType", it) }
    }

    private fun isControl(char: Char): Boolean = char < ' ' || char == '\u007f'

    private fun Char.isAsciiLowerOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || isAsciiLowerOrDigit()

    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }
}
