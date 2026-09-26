package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object TokenNormalizer {
    private val fragmentPattern = Regex("access_token=([^&#\\s]+)")
    private val tokenPattern = Regex("^[A-Za-z0-9._\\-]{10,}$")

    fun normalize(raw: String?): String {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (text.startsWith('{') || text.startsWith('"')) text = unwrapJson(text)
        fragmentPattern.find(text)?.let { text = it.groupValues[1] }
        if (text.startsWith("OAuth ", ignoreCase = true)) text = text.substring("OAuth ".length).trim()
        return if (tokenPattern.matches(text)) text else ""
    }

    private fun unwrapJson(text: String): String {
        val parsed =
            try {
                Json.parseToJsonElement("[$text]") as? JsonArray
            } catch (ignored: SerializationException) {
                null
            }
        return when (val value = parsed?.firstOrNull()) {
            is JsonPrimitive -> if (value.isString) value.content.trim() else text
            is JsonObject -> (value["access_token"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            else -> text
        }
    }
}
