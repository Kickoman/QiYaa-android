package io.github.kickoman.qiyaa.support

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse

data class Fixture(val endpoint: String, val case: String, val status: Int, val body: String) {
    fun response(): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    fun json(): JsonElement = Json.parseToJsonElement(body)

    fun result(): JsonElement = json().jsonObject.getValue("result")
}

object Spec {
    private val statusPrefix = Regex("""^(\d{3})-""")

    val root: File by lazy {
        val path =
            System.getProperty(SPEC_DIR_PROPERTY)
                ?: error("System property $SPEC_DIR_PROPERTY is not set; run the tests through Gradle")
        val dir = File(path)
        check(File(dir, "fixtures/yandex").isDirectory) {
            "No fixtures in $dir: run `git submodule update --init` to check out spec/"
        }
        dir
    }

    fun fixture(endpoint: String, case: String, service: String = YANDEX): Fixture {
        val file = File(root, "fixtures/$service/$endpoint/$case.json")
        check(file.isFile) { "No fixture $file" }
        val status = statusPrefix.find(case)?.groupValues?.get(1)?.toInt() ?: 200
        return Fixture(endpoint, case, status, file.readText())
    }

    fun expected(endpoint: String, case: String, service: String = YANDEX): JsonObject {
        val file = File(root, "expected/$service/$endpoint/$case.json")
        check(file.isFile) { "No expected result $file" }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    fun dsp(name: String): JsonObject {
        val file = File(root, "dsp/$name.json")
        check(file.isFile) { "No DSP vectors $file" }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    fun lyrics(): JsonObject {
        val file = File(root, "lyrics/lrc.json")
        check(file.isFile) { "No lyrics vectors $file" }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    fun spotify(name: String): JsonObject {
        val file = File(root, "spotify/$name.json")
        check(file.isFile) { "No Spotify vectors $file" }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    fun spotifyFiles(): List<String> = File(root, "spotify").listFiles { file -> file.extension == "json" }
        .orEmpty()
        .map { it.nameWithoutExtension }
        .sorted()

    fun cases(endpoint: String, service: String = YANDEX): List<String> =
        File(root, "fixtures/$service/$endpoint").listFiles { file -> file.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()

    const val YANDEX = "yandex"
    const val LRCLIB = "lrclib"
    private const val SPEC_DIR_PROPERTY = "qiyaa.spec.dir"
}
