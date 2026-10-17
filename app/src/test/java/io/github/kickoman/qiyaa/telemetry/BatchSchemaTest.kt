package io.github.kickoman.qiyaa.telemetry

import io.github.kickoman.qiyaa.support.TelemetryHarness
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.MACHINE
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.events
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.sender
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.string
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.types
import io.github.kickoman.qiyaa.support.TelemetrySchema
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BatchSchemaTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the validator agrees with every example of the spec`() {
        val examples = TelemetrySchema.examples()
        val android = examples.count { it.name.startsWith("android-") }
        assertTrue(examples.map(File::getName).toString(), android >= 3)
        for (file in examples) {
            val errors = TelemetrySchema.errors(file.readText())
            if (file.name.startsWith("invalid-")) {
                assertTrue(file.name, errors.isNotEmpty())
            } else {
                assertEquals(file.name, emptyList<String>(), errors)
            }
        }
    }

    @Test
    fun `the start is encoded exactly as in android-start json`() {
        val expected = example("android-start.json").single()
        val start =
            Event.Start(
                Instant.parse(expected.string("at")),
                expected.string("session"),
                "0.6.0",
                StartFields(
                    osVersion = "14",
                    sdk = 34,
                    device = "Google Pixel 7",
                    kernel = "5.10.198-android13-4-00050-g12f3388846c3-ab11920634",
                    arch = "arm64",
                    packaging = StartFields.Packaging.APK,
                    locale = "be_BY",
                    language = StartFields.Language.BE,
                    dpr = 2.625,
                    theme = StartFields.Theme.AMBER,
                    vis = StartFields.Vis.SPECTRUM,
                    equalizer = true,
                    quality = StartFields.Quality.STANDARD,
                    qualityMetered = StartFields.Quality.ECONOMY,
                    loggedIn = true,
                    firstRun = false,
                ),
            )
        assertEquals(expected, EventCodec.encode(start))
    }

    @Test
    fun `TEL-05 the crash from a report is encoded exactly as in android-crash-then-start json`() {
        val expected = example("android-crash-then-start.json").first()
        val report =
            CrashReport(
                session = expected.string("session"),
                version = "0.6.0",
                frames = expected.getValue("frames").jsonArray.map { it.jsonPrimitive.content },
                seconds = 5_423,
                exceptionType = "java.lang.IllegalStateException",
            )
        val read = CrashReport.read(report.text())!!
        assertEquals(expected, EventCodec.encode(read.event(Instant.parse(expected.string("at")))))
    }

    @Test
    fun `every batch of a scripted session validates against the spec schema`() = runTest {
        val telemetry = TelemetryHarness(this, File(folder.root, "telemetry"), server.sender())
        val store = telemetry.store
        val crashed = "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53"
        val hung = "026dd2da-999e-469f-ae7f-2a343875cd3e"
        val ended = "33691813-d7ab-47ec-b621-747be6192eee"
        val native = "0d6e4c8a-6a3b-4f1e-9a57-3c2b1d0e9f84"
        telemetry.writeCrash(CrashReport.of(IllegalStateException("boom"), crashed, "0.6.0", 5_423))
        store.writeMarker(hung, Marker("0.5.0", Counts(seconds = 840)))
        store.writeMarker(ended, Marker("0.5.9", Counts(6_120, 23, 87, 1, 0)))
        store.writeMarker(native, Marker("0.5.9", Counts(seconds = 60)))
        val exits = mapOf(hung to ExitInfo(ExitReason.ANR), native to ExitInfo(ExitReason.NATIVE_CRASH, 11))
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, exits)
        reporter.recordError(ErrorArea.PLAYBACK, "network")
        reporter.recordError(ErrorArea.API, "http", 502)
        reporter.recordError(ErrorArea.JAM, "server-full")
        reporter.recordFeature(FeatureName.JAM, "on")
        reporter.recordFeature(FeatureName.EQUALIZER, "off")
        repeat(60) { reporter.recordFeature(FeatureName.VIS, "spectrum") }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }

        reporter.send()

        val bodies = List(server.requestCount) { server.takeRequest(10, TimeUnit.SECONDS)!!.body.readUtf8() }
        assertEquals(2, bodies.size)
        for (body in bodies) assertEquals(body, emptyList<String>(), TelemetrySchema.errors(body))
        val sent = bodies.flatMap(::events)
        val ends = sent.take(4).map { it.string("type") }
        assertEquals(listOf("crash", "crash", "exit", "unclean_exit"), ends.sorted())
        assertEquals(
            listOf("start", "error", "error", "error", "feature", "feature"),
            sent.drop(4).take(6).map { it.string("type") },
        )
        assertEquals("SIGSEGV", sent.single { it.string("session") == native }.string("signal"))
    }

    @Test
    fun `hostile values in every free field still give a valid batch with the crash in it`() {
        for ((name, inputs) in hostileInputs()) {
            val errors = runHostile(name, inputs)
            assertEquals(name, emptyList<String>(), errors)
        }
    }

    private fun runHostile(name: String, inputs: Inputs): List<String> {
        var errors = emptyList<String>()
        runTest {
            val telemetry = TelemetryHarness(this, folder.newFolder(), server.sender())
            telemetry.writeCrash(EARLIER, inputs.crash)
            val reporter = telemetry.reporter(version = inputs.version)
            reporter.begin(inputs.start, MACHINE, emptyMap())
            reporter.recordError(ErrorArea.API, inputs.kind, inputs.httpStatus)
            reporter.recordFeature(FeatureName.VIS, inputs.feature)
            server.enqueue(MockResponse().setResponseCode(204))

            reporter.send()

            val body = server.takeRequest(10, TimeUnit.SECONDS)!!.body.readUtf8()
            errors = TelemetrySchema.errors(body)
            assertTrue("$name: $body", "crash" in types(body))
            assertTrue("$name: $body", "start" in types(body))
        }
        return errors
    }

    private fun hostileInputs(): List<Pair<String, Inputs>> {
        val good = Inputs()
        fun start(change: StartFields.() -> StartFields) = good.copy(start = good.start.change())
        val header = "session=$EARLIER\nversion=0.6.0\nsignal=TERMINATE\nseconds=1\n"
        fun crash(vararg lines: String) = good.copy(crash = header + lines.joinToString("\n"))
        val thrown =
            IllegalStateException("/sdcard/Кино.mp3").apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("Кино\u0000Class", "m e t", null, 99_999_999),
                        StackTraceElement("a".repeat(400), "run", null, 1),
                        StackTraceElement("", "", null, -2),
                    )
            }
        return listOf(
            "device empty" to start { copy(device = "") },
            "device control" to start { copy(device = "\u0000\u0007") },
            "device Cyrillic" to start { copy(device = "Пиксель") },
            "device long" to start { copy(device = "x".repeat(100)) },
            "arch unmapped" to start { copy(arch = "arm64-v8a") },
            "arch empty" to start { copy(arch = "") },
            "arch symbols" to start { copy(arch = "MIPS!! r6 32-bit long") },
            "locale with a script" to start { copy(locale = "sr_Latn_RS") },
            "locale toString" to start { copy(locale = "sr_RS_#Latn") },
            "locale BCP 47" to start { copy(locale = "en-US") },
            "locale empty" to start { copy(locale = "") },
            "locale upper case" to start { copy(locale = "EN") },
            "osVersion long" to start { copy(osVersion = "x".repeat(40)) },
            "osVersion control" to start { copy(osVersion = "14\n") },
            "osVersion empty" to start { copy(osVersion = "") },
            "kernel long" to start { copy(kernel = "5.10." + "x".repeat(100)) },
            "kernel surrogates" to start { copy(kernel = "😀".repeat(40)) },
            "dpr NaN" to start { copy(dpr = Double.NaN) },
            "dpr infinite" to start { copy(dpr = Double.POSITIVE_INFINITY) },
            "dpr zero" to start { copy(dpr = 0.0) },
            "dpr huge" to start { copy(dpr = 100.0) },
            "sdk zero" to start { copy(sdk = 0) },
            "sdk huge" to start { copy(sdk = 5_000) },
            "version with a space and a slash" to good.copy(version = "0.6.0 beta/1"),
            "version empty" to good.copy(version = ""),
            "version long" to good.copy(version = "1.".repeat(30)),
            "exception message" to crash("exception=java.io.FileNotFoundException: /sdcard/Кино.mp3"),
            "exception long" to crash("exception=" + "a".repeat(200)),
            "exception digit first" to crash("exception=1abc"),
            "frame of the desktop" to crash("frame=QiYaa+0x4f2a10"),
            "frame long" to crash("frame=" + "x".repeat(300) + ":12"),
            "frame Cyrillic" to crash("frame=Кино.play:3"),
            "frame big line" to crash("frame=a.b:123456789"),
            "frame without a place" to crash("frame=:5"),
            "crash of a hostile throwable" to
                good.copy(crash = CrashReport.of(thrown, EARLIER, "0.6.0", 3).text()),
            "kind with spaces" to good.copy(kind = "HTTP 502"),
            "kind a jam refusal text" to good.copy(kind = "Room 7k3m9q2x full"),
            "kind empty" to good.copy(kind = ""),
            "kind long" to good.copy(kind = "x".repeat(40)),
            "httpStatus too small" to good.copy(httpStatus = 42),
            "httpStatus too big" to good.copy(httpStatus = 600),
            "httpStatus negative" to good.copy(httpStatus = -1),
            "feature Cyrillic" to good.copy(feature = "Кино"),
            "feature empty" to good.copy(feature = ""),
            "feature long" to good.copy(feature = "x".repeat(60)),
            "feature slash" to good.copy(feature = "a/b"),
        )
    }

    private fun example(name: String): List<JsonObject> =
        Json.parseToJsonElement(TelemetrySchema.example(name).readText())
            .jsonObject
            .getValue("events")
            .jsonArray
            .map { it.jsonObject }

    private data class Inputs(
        val start: StartFields = StartFields(),
        val version: String = "0.6.0",
        val crash: String = CrashReport(EARLIER, "0.6.0", frames = listOf("a.B.m:1"), seconds = 1).text(),
        val kind: String = "http",
        val httpStatus: Int? = 502,
        val feature: String = "spectrum",
    )

    private companion object {
        const val EARLIER = "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53"
    }
}
