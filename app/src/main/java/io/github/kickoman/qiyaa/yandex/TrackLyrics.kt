package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

object TrackLyrics {
    fun textOf(result: JsonElement): String? {
        val lyrics = result.objectOrEmpty["lyrics"].objectOrEmpty
        val text = lyrics.string("fullLyrics")
        val rights = (lyrics["hasRights"] as? JsonPrimitive)?.booleanOrNull
        return text.takeIf { it.isNotEmpty() && rights != false }
    }
}
