package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.Ids
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class ContextUris(val trackUris: List<String>, val next: String)

object CatalogJson {
    const val MADE_FOR_YOU = "spotify:section:0JQ5DAUnp4wcj0bCb3wh3S"

    fun objectOrNull(body: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
    } catch (ignored: SerializationException) {
        null
    }

    fun context(body: ByteArray): ContextUris? {
        val root = objectOrNull(body) ?: return null
        if ((root[LOADING] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true) return null
        val page = if (PAGES in root) root[PAGES].array().firstOrNull().obj() else root
        val trackUris = page[TRACKS].array()
            .map { it.obj().text(URI) }
            .filter { it.startsWith(Ids.TRACK_URI_PREFIX) }
        return ContextUris(trackUris, page.text(NEXT_PAGE_URL))
    }

    fun pathfinderData(body: ByteArray): JsonObject? = objectOrNull(body)?.get(DATA) as? JsonObject

    fun madeForYou(data: JsonObject): List<SpotifyItem>? {
        val home = data[HOME].obj()
        if (SECTION_CONTAINER !in home) return null
        return home[SECTION_CONTAINER].obj().items(SECTIONS)
            .map { it.obj() }
            .filter { it.text(URI) == MADE_FOR_YOU }
            .flatMap { section -> section.items(SECTION_ITEMS) }
            .map { it.obj()[CONTENT].obj()[DATA].obj() }
            .filter { it.text(TYPENAME) == PLAYLIST }
            .map { SpotifyItem(it.text(URI), it.text(NAME)) }
    }

    fun found(data: JsonObject): SpotifyFound? {
        if (SEARCH !in data) return null
        val search = data[SEARCH].obj()
        val trackUris = search.items(TRACKS_V2)
            .map { dataOf(it.obj()[ITEM].obj()).text(URI) }
            .filter { it.startsWith(Ids.TRACK_URI_PREFIX) }
        val top = search[TOP_RESULTS].obj()[TOP_ITEMS].array()
        if (top.isEmpty()) return SpotifyFound.Tracks(trackUris)
        val best = dataOf(top.first().obj()[ITEM].obj())
        val uri = best.text(URI)
        return when {
            best.text(TYPENAME) == ARTIST && uri.startsWith(Ids.ARTIST_URI_PREFIX) ->
                SpotifyFound.Artist(SpotifyItem(uri, best[PROFILE].obj().text(NAME)), trackUris)

            best.text(TYPENAME) == ALBUM && uri.startsWith(Ids.ALBUM_URI_PREFIX) ->
                SpotifyFound.Album(SpotifyItem(uri, best.text(NAME)), trackUris)

            else -> SpotifyFound.Tracks(trackUris)
        }
    }

    private fun dataOf(wrapper: JsonObject): JsonObject =
        if (DATA in wrapper) wrapper[DATA].obj() else wrapper

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.items(list: String): JsonArray = this[list].obj()[ITEMS].array()

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private const val LOADING = "loading"
    private const val PAGES = "pages"
    private const val TRACKS = "tracks"
    private const val URI = "uri"
    private const val NAME = "name"
    private const val NEXT_PAGE_URL = "next_page_url"
    private const val DATA = "data"
    private const val HOME = "home"
    private const val SECTION_CONTAINER = "sectionContainer"
    private const val SECTIONS = "sections"
    private const val SECTION_ITEMS = "sectionItems"
    private const val ITEMS = "items"
    private const val CONTENT = "content"
    private const val TYPENAME = "__typename"
    private const val PLAYLIST = "Playlist"
    private const val ARTIST = "Artist"
    private const val ALBUM = "Album"
    private const val SEARCH = "searchV2"
    private const val TRACKS_V2 = "tracksV2"
    private const val TOP_RESULTS = "topResultsV2"
    private const val TOP_ITEMS = "itemsV2"
    private const val ITEM = "item"
    private const val PROFILE = "profile"
}
