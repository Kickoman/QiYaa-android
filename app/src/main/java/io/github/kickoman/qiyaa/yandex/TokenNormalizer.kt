package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Port of src/yandex/Token.cpp: accepts a raw token, a JSON string/object with `access_token`,
 * or the redirect URL `https://music.yandex.ru/#access_token=…`, and returns a clean token or "".
 */
object TokenNormalizer {
    private val fragment = Regex("access_token=([^&#\\s]+)")
    private val valid = Regex("^[A-Za-z0-9._\\-]{10,}$")

    fun normalize(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""

        if (s.startsWith('{') || s.startsWith('"')) {
            try {
                val arr = Json.parseToJsonElement("[$s]") as? JsonArray
                when (val v = arr?.firstOrNull()) {
                    is JsonPrimitive -> if (v.isString) s = v.content.trim()
                    is JsonObject -> s = (v["access_token"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    else -> {}
                }
            } catch (_: Exception) {
            }
        }

        fragment.find(s)?.let { s = it.groupValues[1] }

        if (s.startsWith("OAuth ", ignoreCase = true)) s = s.substring(6).trim()

        return if (valid.matches(s)) s else ""
    }
}
