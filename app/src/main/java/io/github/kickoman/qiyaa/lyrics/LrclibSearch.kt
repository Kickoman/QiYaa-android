package io.github.kickoman.qiyaa.lyrics

import kotlin.math.abs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class LrclibRecord(
    val id: Long,
    val durationSeconds: Double,
    val instrumental: Boolean,
    val plainLyrics: String,
    val syncedLyrics: String,
)

data class LrclibChoice(val answer: LyricsAnswer, val recordId: Long?)

object LrclibSearch {
    const val DURATION_TOLERANCE_MS = 2_000L
    private val VERSION = Regex("""^(.*\S)\s+(?:\([^()]*\)|\[[^\[\]]*])\s*$""")

    fun parse(body: String): List<LrclibRecord> {
        val array =
            try {
                Json.parseToJsonElement(body) as? JsonArray
            } catch (ignored: SerializationException) {
                null
            }
        return array.orEmpty().mapNotNull { it as? JsonObject }.map { record ->
            LrclibRecord(
                id = (record["id"] as? JsonPrimitive)?.longOrNull ?: 0,
                durationSeconds = (record["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                instrumental = (record["instrumental"] as? JsonPrimitive)?.booleanOrNull ?: false,
                plainLyrics = record.text("plainLyrics"),
                syncedLyrics = record.text("syncedLyrics"),
            )
        }
    }

    fun pick(records: List<LrclibRecord>, durationMs: Long): LrclibChoice {
        val candidates = records.filter { fits(it, durationMs) }
        for (record in candidates) {
            val synced = LyricsText.parse(record.syncedLyrics)
            if (synced?.kind == LyricsKind.SYNCED) return found(synced, record.id)
        }
        for (record in candidates) {
            val plain = LyricsText.plain(record.plainLyrics) ?: LyricsText.parse(record.syncedLyrics)
            if (plain != null) return found(plain, record.id)
        }
        candidates.firstOrNull {
            it.instrumental
        }?.let { return LrclibChoice(LyricsAnswer.Instrumental, it.id) }
        return LrclibChoice(LyricsAnswer.Missing(), null)
    }

    fun titleWithoutVersion(title: String): String? = VERSION.find(title)?.groupValues?.get(1)

    private fun fits(record: LrclibRecord, durationMs: Long): Boolean =
        durationMs <= 0 || abs(record.durationSeconds * 1000 - durationMs) <= DURATION_TOLERANCE_MS

    private fun found(lyrics: Lyrics, id: Long): LrclibChoice =
        LrclibChoice(LyricsAnswer.Text(lyrics, LyricsFrom.THIRD_PARTY), id)

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
