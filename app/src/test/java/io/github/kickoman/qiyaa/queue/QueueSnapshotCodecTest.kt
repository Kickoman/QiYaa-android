package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `an index outside the tracks is clamped`() {
        val text = QueueSnapshotCodec.encode(snapshot.copy(index = 7))
        assertEquals(1, QueueSnapshotCodec.decode(text)!!.index)
    }
}
