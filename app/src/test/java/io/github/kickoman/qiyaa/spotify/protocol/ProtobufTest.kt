package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import io.github.kickoman.qiyaa.support.Vector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtobufTest {
    @Test
    fun `every case of spec spotify protobuf json writes, reads or is refused as given`() {
        SpotifyVectors.forEachCase("protobuf") { case ->
            when (val kind = case.text("kind")) {
                "write" -> assertEquals(case.text("bytes"), write(case.list("fields")).toByteArray().toHex())

                "read" -> {
                    val (fields, failed) = read(case.hex("bytes"))
                    assertFalse("refused", failed)
                    assertEquals(case.list("fields").map(Vector::fields), fields)
                }

                "reject" -> {
                    val (fields, failed) = read(case.hex("bytes"))
                    assertTrue("not refused", failed)
                    assertEquals(case.list("fieldsBefore").map(Vector::fields), fields)
                }

                else -> fail("unknown kind $kind")
            }
        }
    }

    @Test
    fun `a nested message is read through its own reader that stops at the field's end`() {
        val inner = ProtoWriter().varint(1, 300).string(2, "name")
        val outer = ProtoWriter().message(10, inner).varint(100, 1).toByteArray()
        val reader = ProtoReader(outer)
        assertTrue(reader.next())
        assertEquals(10, reader.field)
        val nested = reader.message()
        assertTrue(nested.next())
        assertEquals(300L, nested.varint)
        assertTrue(nested.next())
        assertEquals("name", nested.string())
        assertFalse(nested.next())
        assertFalse(nested.failed)
        assertTrue(reader.next())
        assertEquals(100, reader.field)
        assertFalse(reader.next())
        assertFalse(reader.failed)
    }

    @Test
    fun `a reader that failed stays failed`() {
        val reader = ProtoReader(byteArrayOf(0x08, 0x01, 0x0b, 0x08, 0x02))
        assertTrue(reader.next())
        assertFalse(reader.next())
        assertFalse(reader.next())
        assertTrue(reader.failed)
    }

    private fun write(fields: List<Vector>): ProtoWriter {
        val writer = ProtoWriter()
        for (field in fields) {
            val number = field.int("field")
            when {
                field.has("varint") -> writer.varint(number, field.unsigned("varint"))
                field.has("string") -> writer.string(number, field.text("string"))
                field.has("bytes") -> writer.bytes(number, field.hex("bytes"))
                field.has("message") -> writer.message(number, write(field.list("message")))
                else -> fail("a field without a value: ${field.fields()}")
            }
        }
        return writer
    }

    private fun read(bytes: ByteArray): Pair<List<Map<String, String>>, Boolean> {
        val reader = ProtoReader(bytes)
        val fields = mutableListOf<Map<String, String>>()
        while (reader.next()) {
            val head = mapOf("field" to reader.field.toString(), "wire" to reader.wire.code.toString())
            fields += head + when (reader.wire) {
                ProtoWire.VARINT -> mapOf(
                    "varint" to java.lang.Long.toUnsignedString(reader.varint),
                    "zigzag" to reader.zigzag.toString(),
                )

                ProtoWire.FIXED32, ProtoWire.FIXED64 -> mapOf(
                    "bytes" to reader.bytes().toHex(),
                    "value" to java.lang.Long.toUnsignedString(reader.varint),
                )

                ProtoWire.BYTES -> mapOf("bytes" to reader.bytes().toHex())
            }
        }
        return fields to reader.failed
    }
}
