package io.github.kickoman.qiyaa.spotify.protocol

import java.io.ByteArrayOutputStream

enum class ProtoWire(val code: Int) {
    VARINT(0),
    FIXED64(1),
    BYTES(2),
    FIXED32(5),
    ;

    companion object {
        fun of(code: Int): ProtoWire? = entries.firstOrNull { it.code == code }
    }
}

class ProtoWriter {
    private val buffer = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, ProtoWire.VARINT)
        raw(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, ProtoWire.BYTES)
        raw(value.size.toLong())
        buffer.write(value)
        return this
    }

    fun bytes(field: Int, value: Bytes): ProtoWriter = bytes(field, value.toByteArray())

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = buffer.toByteArray()

    private fun tag(field: Int, wire: ProtoWire) {
        require(field in 1..MAX_FIELD) { "protobuf field numbers are 1..$MAX_FIELD, got $field" }
        raw((field.toLong() shl 3) or wire.code.toLong())
    }

    private fun raw(value: Long) {
        var rest = value
        while ((rest and 0x7fL.inv()) != 0L) {
            buffer.write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        buffer.write(rest.toInt())
    }

    companion object {
        const val MAX_FIELD = 0x1fffffff
    }
}

class ProtoReader(private val input: ByteArray, start: Int = 0, private val end: Int = input.size) {
    private var position = start
    private var payloadStart = start
    private var payloadEnd = start

    var failed = false
        private set
    var field = 0
        private set
    var wire = ProtoWire.VARINT
        private set
    var varint = 0L
        private set

    val zigzag: Long get() = (varint ushr 1) xor -(varint and 1)

    fun next(): Boolean {
        if (failed || position >= end) return false
        val key = readVarint()
        val type = key?.let { ProtoWire.of((it and 7).toInt()) }
        if (key == null || type == null || key ushr 3 == 0L || key ushr 3 > ProtoWriter.MAX_FIELD) {
            failed = true
            return false
        }
        field = (key ushr 3).toInt()
        wire = type
        varint = 0
        payloadStart = position
        payloadEnd = position
        when (type) {
            ProtoWire.VARINT -> varint = readVarint() ?: return fail()

            ProtoWire.FIXED64, ProtoWire.FIXED32 -> {
                val width = if (type == ProtoWire.FIXED64) 8 else 4
                if (end - position < width) return fail()
                for (i in width - 1 downTo 0) {
                    varint = (varint shl 8) or (input[position + i].toLong() and 0xff)
                }
                payloadEnd = position + width
                position = payloadEnd
            }

            ProtoWire.BYTES -> {
                val length = readVarint() ?: return fail()
                if (length < 0 || length > end - position) return fail()
                payloadStart = position
                payloadEnd = position + length.toInt()
                position = payloadEnd
            }
        }
        return true
    }

    fun bytes(): ByteArray = input.copyOfRange(payloadStart, payloadEnd)

    fun string(): String = String(input, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)

    fun message(): ProtoReader = ProtoReader(input, payloadStart, payloadEnd)

    private fun fail(): Boolean {
        failed = true
        return false
    }

    private fun readVarint(): Long? {
        var value = 0L
        for (shift in 0 until 64 step 7) {
            if (position >= end) return null
            val byte = input[position++].toInt()
            value = value or ((byte and 0x7f).toLong() shl shift)
            if ((byte and 0x80) == 0) return value
        }
        return null
    }
}
