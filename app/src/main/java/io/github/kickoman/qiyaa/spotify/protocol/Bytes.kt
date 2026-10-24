package io.github.kickoman.qiyaa.spotify.protocol

import java.io.ByteArrayOutputStream

class Bytes private constructor(private val data: ByteArray) {
    val size: Int get() = data.size

    fun isEmpty(): Boolean = data.isEmpty()

    operator fun get(index: Int): Byte = data[index]

    fun toByteArray(): ByteArray = data.copyOf()

    fun hex(): String = data.toHex()

    override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "Bytes(size=$size)"

    companion object {
        val EMPTY = Bytes(ByteArray(0))

        fun of(bytes: ByteArray): Bytes = Bytes(bytes.copyOf())

        fun ofHex(hex: String): Bytes? = bytesOfHex(hex)?.let(::Bytes)
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val hex = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return hex.toString()
}

fun bytesOfHex(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val bytes = ByteArray(hex.length / 2)
    for (i in bytes.indices) {
        val high = hexDigit(hex[2 * i])
        val low = hexDigit(hex[2 * i + 1])
        if (high < 0 || low < 0) return null
        bytes[i] = ((high shl 4) or low).toByte()
    }
    return bytes
}

private fun hexDigit(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

internal fun readU16(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)

internal fun readU32(data: ByteArray, at: Int): Int = (readU16(data, at) shl 16) or readU16(data, at + 2)

internal fun readU64(data: ByteArray, at: Int): Long =
    (readU32(data, at).toLong() shl 32) or (readU32(data, at + 4).toLong() and 0xffffffffL)

internal fun writeU64(data: ByteArray, at: Int, value: Long) {
    for (i in 0 until 8) data[at + i] = (value ushr (56 - 8 * i)).toByte()
}

internal fun ByteArrayOutputStream.writeU16(value: Int) {
    write(value ushr 8)
    write(value)
}

internal fun ByteArrayOutputStream.writeU32(value: Int) {
    writeU16(value ushr 16)
    writeU16(value)
}

internal fun stripLeadingZeros(bytes: ByteArray): ByteArray {
    val first = bytes.indexOfFirst { it != 0.toByte() }
    return if (first < 0) ByteArray(0) else bytes.copyOfRange(first, bytes.size)
}
