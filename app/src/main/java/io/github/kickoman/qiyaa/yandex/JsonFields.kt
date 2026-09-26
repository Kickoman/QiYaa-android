package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal val JsonElement?.objectOrEmpty: JsonObject
    get() = this as? JsonObject ?: JsonObject(emptyMap())

internal val JsonElement?.arrayOrEmpty: JsonArray
    get() = this as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.scalarString(key: String): String = idString(this[key])

internal fun JsonObject.int(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: default

internal fun JsonObject.long(key: String, default: Long): Long =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: default

internal fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun parseJsonObjectOrNull(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (ignored: SerializationException) {
    null
}

fun idString(value: JsonElement?): String {
    val primitive = value as? JsonPrimitive ?: return ""
    if (primitive.isString) return primitive.content
    val content = primitive.content
    if (content == "null" || content == "true" || content == "false") return ""
    val number = content.toDoubleOrNull() ?: return content
    val isIntegral = number == Math.floor(number) && !content.contains('e', ignoreCase = true)
    return if (isIntegral) number.toLong().toString() else content
}
