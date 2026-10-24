package io.github.kickoman.qiyaa.spotify.protocol

object AudioFile {
    const val HEADER_SIZE = 167
    val IV: Bytes = Bytes.ofHex("72e067fbddcbcf77ebe8bc643f630d93")!!
}

class AudioDecryptor(key: Bytes) {
    private val cipher = AesCtr(key.toByteArray(), AudioFile.IV.toByteArray())

    fun apply(data: ByteArray, fileOffset: Long, from: Int = 0, length: Int = data.size - from) {
        cipher.apply(data, fileOffset, from, length)
    }
}
