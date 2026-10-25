package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.spotify.protocol.ProtoWriter
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SpotifyFixture(
    val endpoint: String,
    val case: String,
    val status: Int,
    val contentType: String,
    val protobuf: Boolean,
    val body: ByteArray,
) {
    val requestedFrom: Int
        get() = PAGE.find(case)?.groupValues?.get(1)?.toInt()?.let { (it - 1) * LIST_PAGE } ?: 0

    fun response(): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", contentType)
        .setBody(Buffer().write(body))

    private companion object {
        val PAGE = Regex("""^page-(\d+)""")
        const val LIST_PAGE = 1000
    }
}

object SpotifyFixtures {
    private val statusPrefix = Regex("""^(\d{3})-""")
    private val wrapperKeys = setOf("contentType", "base64", "fields")

    fun endpoints(): List<String> = File(Spec.root, "fixtures/$SPOTIFY").listFiles { file ->
        file.isDirectory
    }
        .orEmpty()
        .map { it.name }
        .sorted()

    fun fixture(endpoint: String, case: String): SpotifyFixture {
        val fixture = Spec.fixture(endpoint, case, SPOTIFY)
        val wrapper =
            fixture.body.takeIf { it.isNotBlank() }
                ?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?.takeIf { it.keys == wrapperKeys }
        if (wrapper == null) {
            return SpotifyFixture(endpoint, case, fixture.status, JSON, false, fixture.body.toByteArray())
        }
        val body = Base64.getDecoder().decode(wrapper.getValue("base64").jsonPrimitive.content)
        assertTrue(
            "$endpoint/$case: its fields do not write its base64 body",
            write(wrapper.getValue("fields").jsonArray).contentEquals(body),
        )
        val contentType = wrapper.getValue("contentType").jsonPrimitive.content
        return SpotifyFixture(endpoint, case, fixture.status, contentType, true, body)
    }

    fun expected(endpoint: String, case: String): JsonObject = Spec.expected(endpoint, case, SPOTIFY)

    inline fun forEachCase(endpoint: String, check: (SpotifyFixture) -> JsonObject): Int {
        val cases = Spec.cases(endpoint, SPOTIFY)
        assertTrue("fixtures/spotify/$endpoint has cases", cases.isNotEmpty())
        for (case in cases) {
            val actual = check(fixture(endpoint, case))
            assertEquals("fixtures/spotify/$endpoint/$case", expected(endpoint, case), actual)
        }
        return cases.size
    }

    private fun write(fields: JsonArray): ByteArray {
        val writer = ProtoWriter()
        for (element in fields) {
            val field = element.jsonObject
            val number = field.getValue("field").jsonPrimitive.int
            val value = field.keys.single { it != "field" }
            val text = { field.getValue(value).jsonPrimitive.content }
            when (value) {
                "varint" -> writer.varint(number, java.lang.Long.parseUnsignedLong(text()))
                "string" -> writer.string(number, text())
                "bytes" -> writer.bytes(number, hexBytes(text()))
                "message" -> writer.bytes(number, write(field.getValue(value).jsonArray))
                else -> throw AssertionError("a fixture field of an unknown kind: $field")
            }
        }
        return writer.toByteArray()
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) {
        hex.substring(2 * it, 2 * it + 2).toInt(16).toByte()
    }

    const val SPOTIFY = "spotify"
    const val JSON = "application/json"
}
