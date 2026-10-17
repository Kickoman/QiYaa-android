package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotCodecTest {
    private val snapshot =
        QueueSnapshot(
            tracks =
            listOf(
                Track(
                    "1",
                    "Кукушка",
                    listOf("Кино"),
                    "4053",
                    398_000,
                    true,
                    "https://avatars.yandex.net/x/400x400",
                ),
                Track("2", "Без обложки", emptyList(), "", 0, true, null),
            ),
            batchIds = listOf("B1", ""),
            title = "Моя волна",
            sourceId = "user:onyourwave",
            isWave = true,
            waveSessionId = "S1",
            waveStationId = "user:onyourwave",
            index = 1,
            positionMs = 42_000,
            shuffle = false,
            repeat = true,
        )

    @Test
    fun `a snapshot survives encoding and decoding`() {
        assertEquals(snapshot, QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `an ordinary queue without a source id or a wave survives too`() {
        val plain = snapshot.copy(
            sourceId = null,
            isWave = false,
            waveSessionId = "",
            waveStationId = "",
            batchIds = listOf("", ""),
        )
        assertEquals(plain, QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(plain)))
    }

    @Test
    fun `missing, unknown-version and broken files give no snapshot`() {
        assertNull(QueueSnapshotCodec.decode(null))
        assertNull(QueueSnapshotCodec.decode(""))
        assertNull(QueueSnapshotCodec.decode("not json"))
        assertNull(
            QueueSnapshotCodec.decode(
                QueueSnapshotCodec.encode(snapshot).replace("\"version\":1", "\"version\":99"),
            ),
        )
        assertNull(QueueSnapshotCodec.decode("""{"version":1}"""))
    }

    @Test
    fun `a part is written only where it differs from what isWave implies, and reads back`() {
        val mixed =
            snapshot.copy(
                isWave = false,
                waveSessionId = "",
                waveStationId = "",
                parts = listOf(EntryPart.HEAD, EntryPart.CONTINUATION),
            )
        val text = QueueSnapshotCodec.encode(mixed)
        assertEquals(1, Regex("\"part\"").findAll(text).count())
        assertTrue(text.contains("\"part\":\"continuation\""))
        assertEquals(mixed, QueueSnapshotCodec.decode(text))
    }

    @Test
    fun `an unknown part reads as what isWave implies`() {
        val text =
            QueueSnapshotCodec.encode(snapshot.copy(parts = listOf(EntryPart.CONTINUATION, EntryPart.HEAD)))
                .replace("\"part\":\"head\"", "\"part\":\"later\"")
        assertEquals(
            listOf(EntryPart.CONTINUATION, EntryPart.CONTINUATION),
            QueueSnapshotCodec.decode(text)!!.parts,
        )
    }

    @Test
    fun `a version 1 file of a wave reads as it did before entries had parts`() {
        val expected =
            QueueSnapshot(
                tracks = listOf(
                    Track(
                        "11",
                        "Кукушка",
                        listOf("Кино"),
                        "4053",
                        398_000,
                        true,
                        "https://avatars.yandex.net/x/%%",
                    ),
                    Track("12", "Звезда", listOf("Кино", "Цой"), "4053", 230_000),
                    Track("13", "Without a cover", emptyList(), "", 0, true, null),
                ),
                batchIds = listOf("B1", "B1", "B2"),
                title = "Моя волна",
                sourceId = "user:onyourwave",
                isWave = true,
                waveSessionId = "S1",
                waveStationId = "user:onyourwave",
                index = 2,
                positionMs = 42_000,
                shuffle = true,
                repeat = false,
            )
        assertEquals(expected, QueueSnapshotCodec.decode(VERSION_1_WAVE))
        assertEquals(VERSION_1_WAVE, QueueSnapshotCodec.encode(expected))
    }

    @Test
    fun `a version 1 file of a playlist with shuffle and repeat reads as it did before entries had parts`() {
        val expected =
            QueueSnapshot(
                tracks = listOf(
                    Track("21", "Группа крови", listOf("Кино"), "4054", 285_000),
                    Track("22", "Спокойная ночь", listOf("Кино"), "4054", 367_000),
                ),
                batchIds = listOf("", ""),
                title = "Road",
                sourceId = "playlist:1000:3",
                isWave = false,
                waveSessionId = "",
                waveStationId = "",
                index = 1,
                positionMs = 7_000,
                shuffle = true,
                repeat = true,
            )
        assertEquals(expected, QueueSnapshotCodec.decode(VERSION_1_PLAYLIST))
        assertEquals(VERSION_1_PLAYLIST, QueueSnapshotCodec.encode(expected))
    }

    @Test
    fun `an index outside the tracks is clamped`() {
        val text = QueueSnapshotCodec.encode(snapshot.copy(index = 7))
        assertEquals(1, QueueSnapshotCodec.decode(text)!!.index)
    }

    private companion object {
        const val VERSION_1_WAVE =
            """{"version":1,"title":"Моя волна","sourceId":"user:onyourwave","isWave":true,""" +
                """"waveSessionId":"S1","waveStationId":"user:onyourwave","index":2,""" +
                """"positionMs":42000,"shuffle":true,"repeat":false,"tracks":[""" +
                """{"id":"11","title":"Кукушка","artists":["Кино"],"albumId":"4053",""" +
                """"durationMs":398000,"coverUrl":"https://avatars.yandex.net/x/%%","batchId":"B1"},""" +
                """{"id":"12","title":"Звезда","artists":["Кино","Цой"],"albumId":"4053",""" +
                """"durationMs":230000,"batchId":"B1"},""" +
                """{"id":"13","title":"Without a cover","batchId":"B2"}]}"""

        const val VERSION_1_PLAYLIST =
            """{"version":1,"title":"Road","sourceId":"playlist:1000:3","isWave":false,""" +
                """"index":1,"positionMs":7000,"shuffle":true,"repeat":true,"tracks":[""" +
                """{"id":"21","title":"Группа крови","artists":["Кино"],"albumId":"4054",""" +
                """"durationMs":285000},""" +
                """{"id":"22","title":"Спокойная ночь","artists":["Кино"],"albumId":"4054",""" +
                """"durationMs":367000}]}"""
    }
}
