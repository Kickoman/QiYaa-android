package io.github.kickoman.qiyaa.support

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class Vector(private val json: JsonObject, private val path: String) {
    private val read = mutableSetOf<String>()
    private val objects = mutableMapOf<String, Vector>()
    private val lists = mutableMapOf<String, List<Vector>>()

    val name: String get() = if ("name" in json) text("name") else path

    fun has(key: String): Boolean = key in json

    fun isNull(key: String): Boolean = element(key) is JsonNull

    fun text(key: String): String = element(key).jsonPrimitive.content

    fun int(key: String): Int = element(key).jsonPrimitive.int

    fun long(key: String): Long = element(key).jsonPrimitive.long

    fun longOrNull(key: String): Long? = if (isNull(key)) null else long(key)

    fun bool(key: String): Boolean = element(key).jsonPrimitive.boolean

    fun unsigned(key: String): Long = java.lang.Long.parseUnsignedLong(text(key))

    fun hex(key: String): ByteArray = bytesOf(text(key), key)

    fun hexOrNull(key: String): ByteArray? = if (isNull(key)) null else hex(key)

    fun texts(key: String): List<String> = element(key).jsonArray.map { it.jsonPrimitive.content }

    fun hexes(key: String): List<ByteArray> = texts(key).map { bytesOf(it, key) }

    fun obj(key: String): Vector = objects.getOrPut(key) { Vector(element(key).jsonObject, "$path.$key") }

    fun objOrNull(key: String): Vector? = if (isNull(key)) null else obj(key)

    fun list(key: String): List<Vector> = lists.getOrPut(key) {
        element(key).jsonArray.mapIndexed { i, item -> Vector(item.jsonObject, "$path.$key[$i]") }
    }

    fun fields(): Map<String, String> = json.keys.associateWith { key ->
        val value = element(key)
        (value as? JsonPrimitive)?.content ?: value.toString()
    }

    fun textOrNull(key: String): String? = if (isNull(key)) null else text(key)

    fun unread(): List<String> = json.keys.filter { it !in read }.map { "$path.$it" } +
        objects.values.flatMap { it.unread() } +
        lists.values.flatten().flatMap { it.unread() }

    private fun element(key: String): JsonElement {
        read += key
        return json[key] ?: throw AssertionError("$path has no field $key")
    }

    private fun bytesOf(hex: String, key: String): ByteArray {
        check(hex.length % 2 == 0) { "$path.$key is not hex: $hex" }
        return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

object SpotifyVectors {
    val FILES = listOf("audio", "crypto", "handshake", "ids", "messages", "protobuf", "shannon")

    fun cases(file: String): List<Vector> {
        val root = Vector(Spec.spotify(file), "spotify/$file.json")
        root.text("description")
        return root.list("cases")
    }

    fun forEachCase(file: String, check: (Vector) -> Unit): Int {
        val cases = cases(file)
        assertTrue("spotify/$file.json has cases", cases.isNotEmpty())
        for (case in cases) {
            val name = case.name
            try {
                check(case)
            } catch (failed: AssertionError) {
                throw AssertionError("spotify/$file.json, \"$name\": ${failed.message}", failed)
            }
        }
        val unread = cases.flatMap { it.unread() }
        assertEquals("fields of spotify/$file.json that no check read", emptyList<String>(), unread)
        return cases.size
    }
}
