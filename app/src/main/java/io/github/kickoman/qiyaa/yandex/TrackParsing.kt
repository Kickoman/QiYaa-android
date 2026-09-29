package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object TrackParsing {
    const val COVER_SIZE = "400x400"

    fun parseTrack(element: JsonElement): Track {
        val item = element.objectOrEmpty
        val version = item.string("version")
        val title = item.string("title").let { if (version.isEmpty()) it else "$it ($version)" }
        val firstAlbum = item["albums"].arrayOrEmpty.firstOrNull()?.objectOrEmpty
        val cover =
            firstAlbum?.string("coverUri").orEmpty()
                .ifEmpty { item.string("coverUri") }
                .ifEmpty { item.string("ogImage") }
        return Track(
            id = idString(item["id"]),
            title = title,
            artists = item["artists"].arrayOrEmpty.map { it.objectOrEmpty.string("name") },
            albumId = firstAlbum?.let { idString(it["id"]) }.orEmpty(),
            durationMs = item.long("durationMs", 0L),
            available = item.boolean("available", true),
            coverUrl = coverUrl(cover),
        )
    }

    fun parseTrackArray(element: JsonElement?): List<Track> {
        val items = element as? JsonArray ?: return emptyList()
        return items.map { item ->
            val embedded = (item as? JsonObject)?.get("track")
            parseTrack(if (embedded is JsonObject) embedded else item)
        }
    }

    fun coverUrl(uri: String, size: String = COVER_SIZE): String? {
        if (uri.isEmpty()) return null
        val withSize = uri.replace("%%", size)
        return if (withSize.startsWith("http")) withSize else "https://$withSize"
    }
}
