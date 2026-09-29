package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.yandex.NamedRef
import io.github.kickoman.qiyaa.yandex.PlaylistRef
import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Station
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.TrackParsing
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WheelWave
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Android's Track keeps no album title, year or genre, and stores the cover as a ready URL:
// those fields are dropped from the spec side and coverUri becomes the URL TrackParsing builds.
object SpecJson {
    private val TRACK_FIELDS_NOT_IN_MODEL = setOf("albumTitle", "year", "genre")

    fun track(track: Track): JsonObject = buildJsonObject {
        put("id", track.id)
        put("albumId", track.albumId)
        put("title", track.title)
        put("artists", buildJsonArray { track.artists.forEach { add(JsonPrimitive(it)) } })
        put("durationMs", track.durationMs)
        put("available", track.available)
        put("coverUrl", track.coverUrl?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun tracks(tracks: List<Track>): JsonObject = buildJsonObject {
        put("tracks", JsonArray(tracks.map(::track)))
    }

    fun expectedTrack(expected: JsonObject): JsonObject {
        val coverUri = expected.getValue("coverUri").jsonPrimitive.content
        return buildJsonObject {
            for ((key, value) in expected) {
                if (key in TRACK_FIELDS_NOT_IN_MODEL || key == "coverUri") continue
                put(key, value)
            }
            put("coverUrl", TrackParsing.coverUrl(coverUri)?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    fun expectedTracks(expected: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in expected) {
            put(
                key,
                if (key ==
                    "tracks"
                ) {
                    JsonArray(value.jsonArray.map { expectedTrack(it.jsonObject) })
                } else {
                    value
                },
            )
        }
    }

    fun ids(key: String, ids: List<String>): JsonObject =
        buildJsonObject { put(key, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }

    fun playlists(playlists: List<PlaylistRef>): JsonObject = buildJsonObject {
        put(
            "playlists",
            buildJsonArray {
                for (playlist in playlists) {
                    add(
                        buildJsonObject {
                            put("ownerUid", playlist.ownerUid)
                            put("kind", playlist.kind)
                            put("title", playlist.title)
                            put("trackCount", playlist.trackCount)
                        },
                    )
                }
            },
        )
    }

    fun named(key: String, refs: List<NamedRef>): JsonObject = buildJsonObject {
        put(
            key,
            buildJsonArray {
                refs.forEach {
                    add(
                        buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                        },
                    )
                }
            },
        )
    }

    fun waves(waves: List<WheelWave>): JsonObject = buildJsonObject {
        put(
            "waves",
            buildJsonArray {
                for (wave in waves) {
                    add(
                        buildJsonObject {
                            put("name", wave.name)
                            put("description", wave.description)
                            put("seeds", buildJsonArray { wave.seeds.forEach { add(it) } })
                        },
                    )
                }
            },
        )
    }

    fun stations(stations: List<Station>): JsonObject = buildJsonObject {
        put(
            "stations",
            buildJsonArray {
                for (station in stations) {
                    add(
                        buildJsonObject {
                            put("id", station.id)
                            put("type", station.type)
                            put("name", station.name)
                        },
                    )
                }
            },
        )
    }

    fun wave(batch: WaveBatch): JsonObject = buildJsonObject {
        put("sessionId", batch.sessionId)
        put("batchId", batch.batchId)
        put("tracks", JsonArray(batch.tracks.map(::track)))
    }

    fun search(result: SearchResult): JsonObject = buildJsonObject {
        put("bestType", result.bestType)
        put("bestId", result.bestId)
        put("bestName", result.bestName)
        put("tracks", JsonArray(result.tracks.map(::track)))
    }

    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
}
