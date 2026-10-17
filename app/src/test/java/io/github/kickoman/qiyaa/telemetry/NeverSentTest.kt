package io.github.kickoman.qiyaa.telemetry

import io.github.kickoman.qiyaa.support.TelemetryHarness
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.events
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.sender
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.string
import io.github.kickoman.qiyaa.support.TelemetrySchema
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NeverSentTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `messages, titles, the token, a jam refusal's text and the raw ANDROID_ID never leave the phone`() =
        runTest {
            val telemetry = TelemetryHarness(this, File(folder.root, "telemetry"), server.sender())
            val machine = MachineId.resolve(ANDROID_ID, null, {})
            val earlier = "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53"
            val thrown = RuntimeException("load failed: $MESSAGE", IllegalStateException(MESSAGE))
            telemetry.writeCrash(CrashReport.of(thrown, earlier, "0.6.0", 12))
            val reporter = telemetry.reporter()
            reporter.begin(StartFields(device = "Pixel\u0007 7"), machine, emptyMap())
            reporter.recordError(ErrorArea.JAM, REFUSAL)
            reporter.recordFeature(FeatureName.LANGUAGE, "Кино")
            runCurrent()
            val onDisk = telemetry.directory.listFiles().orEmpty().joinToString("\n") { it.readText() }
            server.enqueue(MockResponse().setResponseCode(204))

            reporter.send()

            val body = server.takeRequest(10, TimeUnit.SECONDS)!!.body.readUtf8()
            val secrets =
                listOf(MESSAGE, "Кино", TOKEN, "/storage", "7k3m9q2x", ANDROID_ID, "\u0007", "\\u0007")
            for (secret in secrets) {
                assertFalse("$secret in $body", body.contains(secret))
                assertFalse("$secret on disk", onDisk.contains(secret))
            }
            assertEquals(emptyList<String>(), TelemetrySchema.errors(body))
            val sent = events(body)
            val crash = sent.single { it.string("type") == "crash" }
            val frames = crash.getValue("frames").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.toString(), frames.all(EventCodec.FRAME::matches))
            assertEquals("java.lang.IllegalStateException", crash.string("exceptionType"))
            val refusal = sent.single { it.string("type") == "error" }
            assertEquals("jam" to "other", refusal.string("area") to refusal.string("kind"))
            assertEquals("Pixel 7", sent.single { it.string("type") == "start" }.string("device"))
            assertTrue(sent.none { it.string("type") == "feature" })
        }

    private companion object {
        const val ANDROID_ID = "3f2b9c1d7a6e5048"
        const val TOKEN = "y0_AgAAAAB1c2VyAAG8XgAAAAEMfaketokenfaketoken"
        const val MESSAGE = "/storage/emulated/0/Music/Кино - Группа крови.mp3 token=$TOKEN"
        const val REFUSAL = "Room 7k3m9q2x full"
    }
}
