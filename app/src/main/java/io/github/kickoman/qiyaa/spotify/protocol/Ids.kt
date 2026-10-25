package io.github.kickoman.qiyaa.spotify.protocol

object Ids {
    const val BASE62_LENGTH = 22
    const val GID_SIZE = 16
    const val FILE_ID_SIZE = 20
    const val TRACK_URI_PREFIX = "spotify:track:"
    const val ALBUM_URI_PREFIX = "spotify:album:"
    const val ARTIST_URI_PREFIX = "spotify:artist:"
    const val PLAYLIST_URI_PREFIX = "spotify:playlist:"
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun gidFromBase62(text: String): Bytes? {
        if (text.length != BASE62_LENGTH) return null
        val gid = ByteArray(GID_SIZE)
        for (char in text) {
            var carry = DIGITS.indexOf(char)
            if (carry < 0) return null
            for (i in gid.indices.reversed()) {
                carry += (gid[i].toInt() and 0xff) * DIGITS.length
                gid[i] = carry.toByte()
                carry = carry ushr 8
            }
            if (carry != 0) return null
        }
        return Bytes.of(gid)
    }

    fun base62FromGid(gid: Bytes): String {
        require(gid.size == GID_SIZE) { "a gid is $GID_SIZE bytes, got ${gid.size}" }
        val value = gid.toByteArray()
        val text = StringBuilder(BASE62_LENGTH)
        repeat(BASE62_LENGTH) {
            var remainder = 0
            for (i in value.indices) {
                val current = (remainder shl 8) or (value[i].toInt() and 0xff)
                value[i] = (current / DIGITS.length).toByte()
                remainder = current % DIGITS.length
            }
            text.append(DIGITS[remainder])
        }
        return text.reverse().toString()
    }

    fun trackUri(base62: String): String? = gidFromBase62(base62)?.let { TRACK_URI_PREFIX + base62 }
}
