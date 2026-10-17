package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.telemetry.Answer
import io.github.kickoman.qiyaa.telemetry.CrashReport
import io.github.kickoman.qiyaa.telemetry.Reporter
import io.github.kickoman.qiyaa.telemetry.Sender
import io.github.kickoman.qiyaa.telemetry.TelemetryStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryHarness(
    private val scope: TestScope,
    val directory: File,
    private val post: suspend (String) -> Answer,
    private val clock: (() -> Long)? = null,
) {
    var uptimeMs = 0L
    val store = TelemetryStore(directory)
    val logged = ArrayList<String>()
    val io = StandardTestDispatcher(scope.testScheduler)

    fun reporter(version: String = VERSION, session: String = UUID.randomUUID().toString()): Reporter =
        Reporter(
            version = version,
            store = store,
            post = post,
            scope = scope.backgroundScope,
            io = io,
            now = { START.plusMillis(uptime()) },
            uptimeMs = ::uptime,
            log = { logged += it },
            session = session,
        )

    fun writeCrash(report: CrashReport) = writeCrash(report.session, report.text())

    fun writeCrash(session: String, text: String) {
        directory.mkdirs()
        store.crashFile(session).writeText(text)
    }

    fun files(): List<String> = directory.list().orEmpty().sorted()

    private fun uptime(): Long = clock?.invoke() ?: uptimeMs

    companion object {
        const val MACHINE = "97eaa8cc2fbe9a456389e6bd9d44a3fb"
        const val VERSION = "0.6.0"
        val START: Instant = Instant.parse("2026-10-17T10:00:00Z")

        fun events(body: String): List<JsonObject> =
            Json.parseToJsonElement(body).jsonObject.getValue("events").jsonArray.map { it.jsonObject }

        fun types(body: String): List<String> = events(body).map { it.string("type") }

        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        fun session(number: Int): String = "00000000-0000-4000-8000-%012d".format(number)

        fun MockWebServer.sender(client: OkHttpClient = OkHttpClient()): suspend (String) -> Answer =
            Sender(client, url("/api/telemetry").toString())::post
    }
}
