package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AudioFileRef
import io.github.kickoman.qiyaa.spotify.protocol.AudioFormat
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.ProtoWriter
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyTrack
import io.github.kickoman.qiyaa.support.SpotifyFixtures
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.support.bytes
import io.github.kickoman.qiyaa.support.expectFailure
import io.github.kickoman.qiyaa.support.fieldOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyFilesTest {
    private val stack = SpotifyStack()
    private val payloads = ConcurrentHashMap<String, ByteArray>()
    private val clock = AtomicLong(1_000_000)

    @Volatile
    private var bitrates = STANDARD

    @Volatile
    private var metadata: (String) -> MockResponse = ::metadataReply

    @Volatile
    private var storage: () -> MockResponse = { storageFixture("cdn") }

    init {
        stack.route("POST", SpotifyLibrary.METADATA_PATH) { request ->
            metadata(fieldOf(fieldOf(request.bytes(), 2), 1).toString(Charsets.UTF_8))
        }
        for (format in ALL_FORMATS) {
            stack.route("GET", storagePath(format)) { storage() }
        }
    }

    @After
    fun tearDown() = stack.close()

    @Test
    fun `SPOT-13 standard takes Ogg Vorbis 320, economy 96, then 160, then any Ogg Vorbis`() {
        val cases =
            listOf(
                Triple(listOf(OGG_96, OGG_160, OGG_320, MP3_320), STANDARD, 320),
                Triple(listOf(OGG_96, OGG_160, OGG_320, MP3_320), ECONOMY, 96),
                Triple(listOf(OGG_96, OGG_160), STANDARD, 160),
                Triple(listOf(OGG_160, OGG_320), ECONOMY, 160),
                Triple(listOf(OGG_96, MP3_320), STANDARD, 96),
                Triple(listOf(OGG_320, AAC_48), ECONOMY, 320),
            )
        for ((formats, asked, kbps) in cases) {
            val picked = SpotifyFiles.pick(SpotifyTrack(gid = gid(1), files = formats.map(::fileOf)), asked)
            assertEquals("$formats in $asked", kbps, picked?.file?.format?.oggVorbisKbps)
        }
        val onlyOthers = SpotifyTrack(gid = gid(1), files = listOf(MP3_320, AAC_48, FLAC).map(::fileOf))
        assertNull(SpotifyFiles.pick(onlyOthers, STANDARD))
        assertNull(SpotifyFiles.pick(onlyOthers, ECONOMY))
    }

    @Test
    fun `SPOT-13 the file of the quality in effect, its key over the AP, then its CDN links in order`() =
        runBlocking {
            stack.loginStored()
            track(A, OGG_96, OGG_320)
            val file = files().prepare(A)
            assertEquals(A, file.trackId)
            assertEquals(320, file.kbps)
            assertEquals(fileId(OGG_320), file.fileId)
            assertEquals(Bytes.of(ByteArray(16) { 0x5a }), file.key)
            assertEquals(
                listOf(
                    "https://audio-fa.scdn.co/audio/0303030303030303030303030303030303030303" +
                        "?1729003600_synthetic-token",
                    "https://audio-ak.spotifycdn.com/audio/0303030303030303030303030303030303030303" +
                        "?__token__=synthetic-token",
                ),
                file.urls,
            )
            val key = stack.accessPoint.keyPackets().single().payload
            assertEquals(fileId(OGG_320), Bytes.of(key.copyOfRange(0, 20)))
            assertEquals(Ids.gidFromBase62(A), Bytes.of(key.copyOfRange(20, 36)))
            assertEquals(1, stack.requests(storagePath(OGG_320)).size)
            assertTrue(file.toString().let { "token" !in it && file.key.hex() !in it })
        }

    @Test
    fun `SPOT-13 a relinked track's key is asked for the gid of the alternative that has the file`() =
        runBlocking {
            stack.loginStored()
            val alternative = gid(9)
            payloads[uriOf(A)] =
                trackPayload(Ids.gidFromBase62(A)!!, emptyList(), alternative to listOf(OGG_160))
            val file = files().prepare(A)
            assertEquals(160, file.kbps)
            val key = stack.accessPoint.keyPackets().single().payload
            assertEquals(fileId(OGG_160), Bytes.of(key.copyOfRange(0, 20)))
            assertEquals(alternative, Bytes.of(key.copyOfRange(20, 36)))
        }

    @Test
    fun `SPOT-15 a track with only MP3, AAC or FLAC, or with no metadata, has no playable file`() =
        runBlocking {
            stack.loginStored()
            track(A, MP3_320, AAC_48, FLAC)
            val failed = expectFailure<NoPlayableFileException> { files().prepare(A) }
            assertEquals(A, failed.trackId)
            assertEquals("Spotify track $A has no Ogg Vorbis file to play", failed.message)
            expectFailure<NoPlayableFileException> { files().prepare(B) }
            assertEquals(emptyList<Any>(), stack.accessPoint.keyPackets())
            assertEquals(emptyList<SpotifyProblem>(), stack.problems.value)
        }

    @Test
    fun `SPOT-15 storage-resolve without CDN links, restricted or from storage is no playable file`() =
        runBlocking {
            stack.loginStored()
            track(A, OGG_320)
            for (case in listOf("restricted", "storage", "cdn-without-urls")) {
                storage = { storageFixture(case) }
                val failed = expectFailure<NoPlayableFileException> { files().prepare(A) }
                assertTrue("$case: ${failed.message}", failed.message!!.contains("no CDN link"))
            }
            storage = { storageReply(0, "not a link", "https://audio-fa.scdn.co/audio/x?t=1") }
            assertEquals(listOf("https://audio-fa.scdn.co/audio/x?t=1"), files().prepare(A).urls)
            assertEquals(emptyList<SpotifyProblem>(), stack.problems.value)
        }

    @Test
    fun `an unreadable storage-resolve or extended-metadata reply is a track failure, reported as Content`() =
        runBlocking {
            stack.loginStored()
            track(A, OGG_320)
            storage = { SpotifyStack.protobuf(BROKEN) }
            val unreadable = expectFailure<SpotifyContentException> { files().prepare(A) }
            assertEquals(
                "GET ${storagePath(OGG_320)}: a reply that does not parse",
                unreadable.message,
            )
            assertEquals(listOf<SpotifyProblem>(SpotifyProblem.Content), stack.awaitProblems(1))
            metadata = { SpotifyStack.protobuf(BROKEN) }
            expectFailure<SpotifyContentException> { files().prepare(A) }
            assertEquals(listOf(SpotifyProblem.Content, SpotifyProblem.Content), stack.awaitProblems(2))
        }

    @Test
    fun `SPOT-17 SPOT-16 key failures come as they are, and a failed first open is not kept`() = runBlocking {
        stack.loginStored()
        track(A, OGG_320)
        val files = files()
        stack.accessPoint.keyRefusal = { 1 }
        expectFailure<KeyRefusedException> { files.prepare(A) }
        stack.accessPoint.keyRefusal = { 2 }
        expectFailure<KeyFailedException> { files.prepare(A) }
        stack.accessPoint.keyRefusal = { null }
        assertEquals(320, files.prepare(A).kbps)
        assertEquals(3, stack.requests(SpotifyLibrary.METADATA_PATH).size)
    }

    @Test
    fun `a first open within 15 minutes asks nothing again, later or in the other quality it asks`() =
        runBlocking {
            stack.loginStored()
            track(A, OGG_96, OGG_320)
            val files = files()
            val first = files.prepare(A)
            clock.addAndGet(SpotifyFiles.TTL_MS - 1)
            assertTrue(first === files.prepare(A))
            assertEquals(1, stack.requests(SpotifyLibrary.METADATA_PATH).size)
            bitrates = ECONOMY
            assertEquals(96, files.prepare(A).kbps)
            bitrates = STANDARD
            assertTrue(first === files.prepare(A))
            clock.addAndGet(1)
            assertFalse(first === files.prepare(A))
            assertEquals(3, stack.requests(SpotifyLibrary.METADATA_PATH).size)
            assertEquals(3, stack.accessPoint.keyPackets().size)
        }

    @Test
    fun `the cache keeps the latest tracks up to its capacity`() = runBlocking {
        stack.loginStored()
        for (id in listOf(A, B, C)) track(id, OGG_320)
        val files = files(capacity = 2)
        for (id in listOf(A, B, C, C, B, A)) files.prepare(id)
        assertEquals(4, stack.requests(SpotifyLibrary.METADATA_PATH).size)
    }

    @Test
    fun `QUAL-05 a track is asked in another quality when its last first open asked other bitrates`() =
        runBlocking {
            stack.loginStored()
            track(A, OGG_96, OGG_320)
            val files = files()
            assertFalse(files.askedInOtherQuality(A))
            files.prepare(A)
            assertFalse(files.askedInOtherQuality(A))
            bitrates = ECONOMY
            assertTrue(files.askedInOtherQuality(A))
            files.prepare(A)
            assertFalse(files.askedInOtherQuality(A))
            metadata = { MockResponse().setResponseCode(500) }
            bitrates = STANDARD
            expectFailure<SpotifyHttpException> { files.prepare(B) }
            bitrates = ECONOMY
            assertTrue(files.askedInOtherQuality(B))
            assertFalse(files.askedInOtherQuality(C))
        }

    @Test
    fun `SPOT-14 storageUrls asks storage-resolve once more for the same file`() = runBlocking {
        stack.loginStored()
        track(A, OGG_320)
        val files = files()
        val file = files.prepare(A)
        storage = { storageReply(0, "https://audio-gm-off.spotifycdn.com/audio/fresh?t=2") }
        assertEquals(listOf("https://audio-gm-off.spotifycdn.com/audio/fresh?t=2"), files.storageUrls(file))
        assertEquals(2, stack.requests(storagePath(OGG_320)).size)
        assertEquals(1, stack.accessPoint.keyPackets().size)
    }

    private fun files(capacity: Int = SpotifyFiles.CAPACITY) =
        SpotifyFiles(stack.web, stack.session, { bitrates }, clock::get, capacity = capacity)

    private fun track(id: String, vararg formats: Int) {
        payloads[uriOf(id)] = trackPayload(Ids.gidFromBase62(id)!!, formats.toList())
    }

    private fun trackPayload(
        gid: Bytes,
        formats: List<Int>,
        alternative: Pair<Bytes, List<Int>>? = null,
    ): ByteArray {
        val track = ProtoWriter().bytes(1, gid.toByteArray()).string(2, "T")
        for (format in formats) track.message(12, fileMessage(format))
        if (alternative != null) {
            val other = ProtoWriter().bytes(1, alternative.first.toByteArray())
            for (format in alternative.second) other.message(12, fileMessage(format))
            track.message(13, other)
        }
        return track.toByteArray()
    }

    private fun fileMessage(format: Int): ProtoWriter =
        ProtoWriter().bytes(1, fileId(format).toByteArray()).varint(2, format.toLong())

    private fun metadataReply(uri: String): MockResponse {
        val payload = payloads[uri]
        val header = ProtoWriter().varint(1, if (payload == null) 404 else 200)
        val data = ProtoWriter().message(1, header).string(2, uri)
        if (payload != null) data.message(3, ProtoWriter().string(1, TYPE_URL).bytes(2, payload))
        return SpotifyStack.protobuf(ProtoWriter().message(2, ProtoWriter().message(3, data)).toByteArray())
    }

    private fun storageFixture(case: String): MockResponse =
        SpotifyFixtures.fixture("storage-resolve-files-audio-interactive", case).response()

    private fun storageReply(result: Int, vararg urls: String): MockResponse {
        val reply = ProtoWriter().varint(1, result.toLong())
        for (url in urls) reply.string(2, url)
        return SpotifyStack.protobuf(reply.toByteArray())
    }

    private fun fileOf(format: Int) = AudioFileRef(fileId(format), AudioFormat(format))

    private fun fileId(format: Int): Bytes = Bytes.of(ByteArray(Ids.FILE_ID_SIZE) { (format + 1).toByte() })

    private fun storagePath(format: Int): String = SpotifyFiles.STORAGE_RESOLVE_PATH + fileId(format).hex()

    private fun gid(mark: Int): Bytes = Bytes.of(ByteArray(Ids.GID_SIZE) { mark.toByte() })

    private fun uriOf(id: String): String = Ids.TRACK_URI_PREFIX + id

    private companion object {
        const val A = "6PCoZdjSPbRMehHviTrpB3"
        const val B = "0oCRBoaQNptb6zOx7UMdad"
        const val C = "01TrY6CZi6JaolPzVQ3HW1"
        const val OGG_96 = 0
        const val OGG_160 = 1
        const val OGG_320 = 2
        const val MP3_320 = 4
        const val AAC_48 = 9
        const val FLAC = 16
        val ALL_FORMATS = listOf(OGG_96, OGG_160, OGG_320, MP3_320, AAC_48, FLAC)
        val STANDARD = listOf(320, 160)
        val ECONOMY = listOf(96, 160)
        val BROKEN = byteArrayOf(0x08, 0xff.toByte())
        const val TYPE_URL = "type.googleapis.com/x"
    }
}
