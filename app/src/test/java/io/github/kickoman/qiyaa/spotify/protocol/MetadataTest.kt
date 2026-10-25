package io.github.kickoman.qiyaa.spotify.protocol

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataTest {
    @Test
    fun `pickOggVorbis takes the first of two files equally close to the bitrate asked`() {
        val twins = SpotifyTrack(files = listOf(file(1, OGG_160), file(2, OGG_160)))
        assertEquals(fileId(1), Metadata.pickOggVorbis(twins, 160)?.file?.id)
        assertEquals(fileId(1), Metadata.pickOggVorbis(twins, 320)?.file?.id)
        val apart = SpotifyTrack(files = listOf(file(3, AudioFormat.OGG_VORBIS_96), file(4, OGG_320)))
        assertEquals(fileId(3), Metadata.pickOggVorbis(apart, 208)?.file?.id)
    }

    @Test
    fun `alternatives deeper than four are not read, so a file only there does not play`() {
        for (depth in 1..Metadata.MAX_ALTERNATIVE_DEPTH) {
            val track = Metadata.parseTrack(Bytes.of(nested(depth)))!!
            assertEquals("depth $depth", fileId(9), Metadata.pickOggVorbis(track, 320)?.file?.id)
        }
        val tooDeep = Metadata.parseTrack(Bytes.of(nested(Metadata.MAX_ALTERNATIVE_DEPTH + 1)))!!
        assertNull(Metadata.pickOggVorbis(tooDeep, 320))
    }

    @Test
    fun `a hundred thousand nested alternatives parse without overflowing the stack`() {
        val track = Metadata.parseTrack(Bytes.of(nested(100_000)))
        assertNotNull(track)
        assertNull(Metadata.pickOggVorbis(track!!, 320))
    }

    @Test
    fun `pickOggVorbis looks no deeper than four alternatives in a track built by hand`() {
        val playable = SpotifyTrack(files = listOf(file(9, OGG_320)))
        val atLimit = (1..Metadata.MAX_ALTERNATIVE_DEPTH).fold(playable) { inner, _ ->
            SpotifyTrack(alternatives = listOf(inner))
        }
        assertEquals(fileId(9), Metadata.pickOggVorbis(atLimit, 320)?.file?.id)
        assertNull(Metadata.pickOggVorbis(SpotifyTrack(alternatives = listOf(atLimit)), 320))
    }

    private fun file(mark: Int, format: AudioFormat): AudioFileRef = AudioFileRef(fileId(mark), format)

    private fun fileId(mark: Int): Bytes = Bytes.of(ByteArray(Ids.FILE_ID_SIZE) { mark.toByte() })

    private fun nested(depth: Int): ByteArray {
        val file = ProtoWriter().bytes(1, fileId(9)).varint(2, OGG_320.code.toLong())
        val innermost = ProtoWriter().message(12, file).toByteArray()
        val prefixes = ArrayList<ByteArray>(depth)
        var length = innermost.size.toLong()
        repeat(depth) {
            val prefix = byteArrayOf(ALTERNATIVE_TAG) + varint(length)
            prefixes += prefix
            length += prefix.size
        }
        val bytes = ByteArrayOutputStream()
        for (prefix in prefixes.asReversed()) bytes.write(prefix)
        bytes.write(innermost)
        return bytes.toByteArray()
    }

    private fun varint(value: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        var rest = value
        while (rest >= 0x80) {
            bytes.write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        bytes.write(rest.toInt())
        return bytes.toByteArray()
    }

    private companion object {
        const val ALTERNATIVE_TAG: Byte = (13 shl 3 or 2).toByte()
        val OGG_160 = AudioFormat.OGG_VORBIS_160
        val OGG_320 = AudioFormat.OGG_VORBIS_320
    }
}
