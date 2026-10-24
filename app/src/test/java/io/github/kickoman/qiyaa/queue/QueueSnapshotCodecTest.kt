package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a track's source survives encoding and decoding, and only a source other than Yandex is written`() {
        val local = Track("42", "Свой файл", listOf("Гурт"), durationMs = 180_000, source = TrackSource.LOCAL)
        val mixed = snapshot.copy(tracks = snapshot.tracks + local, batchIds = snapshot.batchIds + "")
        val text = QueueSnapshotCodec.encode(mixed)
        assertEquals(1, Regex("\"source\"").findAll(text).count())
        assertTrue(text, "\"source\":\"local\"" in text)
        assertEquals(mixed, QueueSnapshotCodec.decode(text))
    }

    @Test
    fun `a queue of Yandex tracks is written without a source, and a file without one reads as Yandex`() {
        val text = QueueSnapshotCodec.encode(snapshot)
        assertFalse(text, "\"source\"" in text)
        assertEquals(
            listOf(TrackSource.YANDEX, TrackSource.YANDEX),
            QueueSnapshotCodec.decode(text)?.tracks?.map {
                it.source
            },
        )
    }

    @Test
    fun `a local track's bitrate and a queue without a vibe from history survive, written only then`() {
        val local = Track("42", "Свой файл", source = TrackSource.LOCAL, bitrateKbps = 1411)
        val files = snapshot.copy(
            tracks = listOf(local),
            batchIds = listOf(""),
            index = 0,
            waveFromHistory = false,
        )
        val text = QueueSnapshotCodec.encode(files)
        assertTrue(text, "\"bitrateKbps\":1411" in text && "\"waveFromHistory\":false" in text)
        assertEquals(files, QueueSnapshotCodec.decode(text))
        val plain = QueueSnapshotCodec.encode(snapshot)
        assertFalse(plain, "bitrateKbps" in plain || "waveFromHistory" in plain)
    }

    @Test
    fun `a source this build does not know gives no snapshot, not a Yandex track`() {
        val local = Track("42", "Свой файл", source = TrackSource.LOCAL)
        val text = QueueSnapshotCodec.encode(snapshot.copy(tracks = listOf(local), batchIds = listOf("")))
        assertNull(QueueSnapshotCodec.decode(text.replace("\"source\":\"local\"", "\"source\":\"spotify\"")))
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
    fun `CONT-01 CONT-05 a head with a vibe from history survives encoding and decoding`() {
        val vibe =
            snapshot.copy(
                isWave = false,
                waveSessionId = "V1",
                waveStationId = "track:7",
                batchIds = listOf("", "B1"),
                parts = listOf(EntryPart.HEAD, EntryPart.CONTINUATION),
                continueWithVibe = true,
                history = listOf("7", "1"),
            )
        val text = QueueSnapshotCodec.encode(vibe)
        assertTrue(text.contains("\"continueWithVibe\":true"))
        assertTrue(text.contains("\"history\":[\"7\",\"1\"]"))
        assertTrue(text.contains("\"waveSessionId\":\"V1\""))
        assertEquals(vibe, QueueSnapshotCodec.decode(text))
        val off = vibe.copy(continueWithVibe = false, history = emptyList())
        assertEquals(off, QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(off)))
    }

    @Test
    fun `CONT-01 a version 1 file without the flag reads with no flag and no history`() {
        for (text in listOf(VERSION_1_WAVE, VERSION_1_PLAYLIST)) {
            val decoded = QueueSnapshotCodec.decode(text)!!
            assertNull(decoded.continueWithVibe)
            assertEquals(emptyList<String>(), decoded.history)
        }
    }

    @Test
    fun `an old jam's version 1 file reads as head and continuation rows, its jam fields ignored`() {
        val expected =
            QueueSnapshot(
                tracks = listOf(
                    Track("31", "Группа крови", listOf("Кино"), "4054", 285_000),
                    Track("32", "Кукушка", listOf("Кино"), "4053", 398_000),
                    Track("33", "Звезда", emptyList(), "", 0),
                    Track("34", "Пачка сигарет", emptyList(), "", 0),
                ),
                batchIds = listOf("", "", "", "B1"),
                title = "Jam",
                sourceId = null,
                isWave = false,
                waveSessionId = "",
                waveStationId = "",
                index = 1,
                positionMs = 5_000,
                shuffle = true,
                repeat = false,
                parts = listOf(EntryPart.HEAD, EntryPart.HEAD, EntryPart.HEAD, EntryPart.CONTINUATION),
                continueWithVibe = true,
                history = listOf("32", "31"),
            )
        assertEquals(expected, QueueSnapshotCodec.decode(VERSION_1_OLD_JAM))
        assertEquals(VERSION_1_OLD_JAM_WRITTEN_BACK, QueueSnapshotCodec.encode(expected))
    }

    @Test
    fun `HOST-23 item marks, waiting and the seeds version survive encoding and decoding`() {
        val jam =
            QueueSnapshot(
                tracks = listOf(
                    Track("41", "Группа крови"),
                    Track("42", "Кукушка"),
                    Track("43", "Звезда"),
                    Track("44", "Восьмиклассница"),
                    Track("45", "Пачка сигарет"),
                    Track("46", "Спокойная ночь"),
                ),
                batchIds = listOf("", "", "", "", "", "B1"),
                title = "Liked",
                sourceId = "liked",
                isWave = false,
                waveSessionId = "SJ",
                waveStationId = "genre:jam",
                index = 1,
                positionMs = 3_000,
                shuffle = false,
                repeat = false,
                parts = listOf(
                    EntryPart.OTHER,
                    EntryPart.ITEM,
                    EntryPart.ITEM,
                    EntryPart.ITEM,
                    EntryPart.HEAD,
                    EntryPart.CONTINUATION,
                ),
                items = listOf(
                    null,
                    ItemMark("i1", "g1", waiting = false),
                    ItemMark("i2", "g2", waiting = true),
                    ItemMark(null, "g1", waiting = true),
                    null,
                    null,
                ),
                seedsVersions = listOf(null, null, null, null, null, 3),
                continueWithVibe = true,
            )
        val text = QueueSnapshotCodec.encode(jam)
        assertEquals(jam, QueueSnapshotCodec.decode(text))
        assertTrue(text.contains("{\"id\":\"41\",\"title\":\"Группа крови\",\"part\":\"other\"}"))
        assertTrue(text.contains("\"part\":\"item\",\"itemId\":\"i1\",\"addedBy\":\"g1\"}"))
        assertTrue(text.contains("\"part\":\"item\",\"itemId\":\"i2\",\"addedBy\":\"g2\",\"waiting\":true}"))
        assertTrue(
            text.contains(
                "\"title\":\"Восьмиклассница\",\"part\":\"item\",\"addedBy\":\"g1\",\"waiting\":true}",
            ),
        )
        assertTrue(text.contains("\"part\":\"continuation\",\"seedsVersion\":3}"))
        assertFalse(text.contains("\"jam"))
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

        const val VERSION_1_OLD_JAM =
            """{"version":1,"title":"Jam","isWave":false,"index":1,"positionMs":5000,"shuffle":true,""" +
                """"repeat":false,"jam":true,"continueWithVibe":true,"history":["32","31"],"tracks":[""" +
                """{"id":"31","title":"Группа крови","artists":["Кино"],"albumId":"4054",""" +
                """"durationMs":285000},""" +
                """{"id":"32","title":"Кукушка","artists":["Кино"],"albumId":"4053","durationMs":398000,""" +
                """"jamItemId":"i1","jamAddedBy":"g1"},""" +
                """{"id":"33","title":"Звезда","jamItemId":"i2","jamAddedBy":"g2"},""" +
                """{"id":"34","title":"Пачка сигарет","batchId":"B1","jamWave":true,"part":"continuation"}]}"""

        const val VERSION_1_OLD_JAM_WRITTEN_BACK =
            """{"version":1,"title":"Jam","isWave":false,"index":1,"positionMs":5000,"shuffle":true,""" +
                """"repeat":false,"continueWithVibe":true,"history":["32","31"],"tracks":[""" +
                """{"id":"31","title":"Группа крови","artists":["Кино"],"albumId":"4054",""" +
                """"durationMs":285000},""" +
                """{"id":"32","title":"Кукушка","artists":["Кино"],"albumId":"4053","durationMs":398000},""" +
                """{"id":"33","title":"Звезда"},""" +
                """{"id":"34","title":"Пачка сигарет","batchId":"B1","part":"continuation"}]}"""

        const val VERSION_1_PLAYLIST =
            """{"version":1,"title":"Road","sourceId":"playlist:1000:3","isWave":false,""" +
                """"index":1,"positionMs":7000,"shuffle":true,"repeat":true,"tracks":[""" +
                """{"id":"21","title":"Группа крови","artists":["Кино"],"albumId":"4054",""" +
                """"durationMs":285000},""" +
                """{"id":"22","title":"Спокойная ночь","artists":["Кино"],"albumId":"4054",""" +
                """"durationMs":367000}]}"""
    }
}
