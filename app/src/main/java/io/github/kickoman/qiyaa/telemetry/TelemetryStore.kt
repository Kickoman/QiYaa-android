package io.github.kickoman.qiyaa.telemetry

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class Marker(val version: String, val counts: Counts = Counts())

class TelemetryStore(private val directory: File) {
    fun sessions(): List<String> {
        val files = directory.listFiles().orEmpty().filter { FILE_NAME.matches(it.name) }
        return files.groupBy { it.name.substringBefore('.') }
            .map { (session, its) -> session to its.minOf(File::lastModified) }
            .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            .map { it.first }
    }

    fun readQueue(session: String): List<String> {
        val text = read(file(session, QUEUE)) ?: return emptyList()
        return text.split('\n').mapNotNull { line -> parseObject(line)?.toString() }
    }

    fun writeQueue(session: String, lines: List<String>) {
        val file = file(session, QUEUE)
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        write(file, lines.joinToString("\n", postfix = "\n"))
    }

    fun readMarker(session: String): Marker? {
        val json = read(file(session, MARKER))?.let(::parseObject) ?: return null
        val version = (json[KEY_VERSION] as? JsonPrimitive)?.contentOrNull ?: return null
        if (!EventCodec.VERSION.matches(version)) return null
        fun count(key: String): Long = (json[key] as? JsonPrimitive)?.longOrNull?.let(EventCodec::count) ?: 0
        return Marker(
            version,
            Counts(
                seconds = count(KEY_SECONDS),
                tracks = count(KEY_TRACKS),
                playMinutes = count(KEY_PLAY_MINUTES),
                jams = count(KEY_JAMS),
                errorsDropped = count(KEY_ERRORS_DROPPED),
                lyrics =
                    LyricsCounts(
                        sourceSynced = count(EventCodec.LYRICS_SOURCE_SYNCED),
                        thirdPartySynced = count(EventCodec.LYRICS_THIRD_PARTY_SYNCED),
                        sourceText = count(EventCodec.LYRICS_SOURCE_TEXT),
                        thirdPartyText = count(EventCodec.LYRICS_THIRD_PARTY_TEXT),
                        instrumental = count(EventCodec.LYRICS_INSTRUMENTAL),
                        none = count(EventCodec.LYRICS_NONE),
                        thirdPartyFailed = count(EventCodec.LYRICS_THIRD_PARTY_FAILED),
                    ),
            ),
        )
    }

    fun writeMarker(session: String, marker: Marker) {
        val json =
            buildJsonObject {
                put(KEY_VERSION, marker.version)
                put(KEY_SECONDS, marker.counts.seconds)
                put(KEY_TRACKS, marker.counts.tracks)
                put(KEY_PLAY_MINUTES, marker.counts.playMinutes)
                put(KEY_JAMS, marker.counts.jams)
                put(KEY_ERRORS_DROPPED, marker.counts.errorsDropped)
                val lyrics = marker.counts.lyrics
                put(EventCodec.LYRICS_SOURCE_SYNCED, lyrics.sourceSynced)
                put(EventCodec.LYRICS_THIRD_PARTY_SYNCED, lyrics.thirdPartySynced)
                put(EventCodec.LYRICS_SOURCE_TEXT, lyrics.sourceText)
                put(EventCodec.LYRICS_THIRD_PARTY_TEXT, lyrics.thirdPartyText)
                put(EventCodec.LYRICS_INSTRUMENTAL, lyrics.instrumental)
                put(EventCodec.LYRICS_NONE, lyrics.none)
                put(EventCodec.LYRICS_THIRD_PARTY_FAILED, lyrics.thirdPartyFailed)
            }
        write(file(session, MARKER), json.toString())
    }

    fun readCrash(session: String): CrashReport? {
        val file = crashFile(session)
        if (file.length() > CrashReport.MAX_CHARS) return null
        return read(file)?.let(CrashReport::read)
    }

    fun crashFile(session: String): File = file(session, CRASH)

    fun remove(session: String) {
        for (suffix in listOf(QUEUE, MARKER, CRASH)) {
            file(session, suffix).delete()
            File(directory, "$session.$suffix${CrashHandler.TEMPORARY_SUFFIX}").delete()
        }
    }

    fun removeAll() {
        directory.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
    }

    private fun file(session: String, suffix: String): File = File(directory, "$session.$suffix")

    private fun read(file: File): String? = try {
        file.readText()
    } catch (ignored: IOException) {
        null
    }

    private fun write(file: File, text: String) {
        directory.mkdirs()
        val temporary = File(directory, file.name + CrashHandler.TEMPORARY_SUFFIX)
        temporary.writeText(text)
        if (!temporary.renameTo(file)) throw IOException("Cannot rename $temporary to $file")
    }

    private fun parseObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (ignored: SerializationException) {
            null
        }
    }

    companion object {
        const val QUEUE = "queue"
        const val MARKER = "json"
        const val CRASH = "crash"
        const val KEY_VERSION = "version"
        const val KEY_SECONDS = "seconds"
        const val KEY_TRACKS = "tracks"
        const val KEY_PLAY_MINUTES = "playMinutes"
        const val KEY_JAMS = "jams"
        const val KEY_ERRORS_DROPPED = "errorsDropped"
        private val FILE_NAME =
            Regex(
                "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.($QUEUE|$MARKER|$CRASH)$",
            )
    }
}
