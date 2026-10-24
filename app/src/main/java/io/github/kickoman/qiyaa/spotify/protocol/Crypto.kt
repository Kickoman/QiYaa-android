package io.github.kickoman.qiyaa.spotify.protocol

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    const val SHA1_SIZE = 20
    const val MAX_MODULUS_BYTES = 512
    private const val HMAC_SHA1 = "HmacSHA1"
    private const val MIN_PADDING = 8
    private val DIGEST_INFOS = listOf(
        bytesOfHex("3021300906052b0e03021a05000414")!!,
        bytesOfHex("301f300706052b0e03021a0414")!!,
    )

    fun sha1(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        parts.forEach(digest::update)
        return digest.digest()
    }

    fun hmacSha1(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA1)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, HMAC_SHA1))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray? {
        val odd = modulus.isNotEmpty() && (modulus.last().toInt() and 1) == 1
        if (!odd || modulus.size > MAX_MODULUS_BYTES) return null
        val m = BigInteger(1, modulus)
        val b = BigInteger(1, base)
        if (b >= m) return null
        return b.modPow(BigInteger(1, exponent), m).toBytes(modulus.size)
    }

    fun rsaPublic(modulus: ByteArray, exponent: Int, signature: ByteArray): ByteArray? {
        if (signature.size > modulus.size) return null
        val n = BigInteger(1, modulus)
        val s = BigInteger(1, signature)
        if (s >= n) return null
        return s.modPow(BigInteger.valueOf(exponent.toLong() and 0xffffffffL), n).toBytes(modulus.size)
    }

    fun verifyRsaSha1(modulus: ByteArray, exponent: Int, digest: ByteArray, signature: ByteArray): Boolean {
        val encoded = rsaPublic(modulus, exponent, signature) ?: return false
        return DIGEST_INFOS.any { digestInfo ->
            val padding = encoded.size - 3 - digestInfo.size - digest.size
            padding >= MIN_PADDING &&
                encoded.contentEquals(
                    byteArrayOf(0, 1) + ByteArray(padding) { -1 } + byteArrayOf(0) + digestInfo + digest,
                )
        }
    }

    private fun BigInteger.toBytes(size: Int): ByteArray {
        val bytes = stripLeadingZeros(toByteArray())
        return ByteArray(size - bytes.size) + bytes
    }
}

class AesCtr(key: ByteArray, iv: ByteArray) {
    private val ivHigh: Long
    private val ivLow: Long

    @Suppress("GetInstance")
    private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding")

    init {
        require(key.size == BLOCK) { "an AES-128 key is $BLOCK bytes, got ${key.size}" }
        require(iv.size == BLOCK) { "the counter is $BLOCK bytes, got ${iv.size}" }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        ivHigh = readU64(iv, 0)
        ivLow = readU64(iv, 8)
    }

    @Synchronized
    fun apply(data: ByteArray, offset: Long, from: Int = 0, length: Int = data.size - from) {
        require(offset >= 0) { "the stream offset is not negative, got $offset" }
        var done = 0
        while (done < length) {
            val position = offset + done
            val within = (position and (BLOCK - 1).toLong()).toInt()
            val blocks = minOf(MAX_BLOCKS, (within.toLong() + (length - done) + BLOCK - 1) / BLOCK).toInt()
            val stream = keystream(position ushr 4, blocks)
            val count = minOf(blocks * BLOCK - within, length - done)
            for (i in 0 until count) {
                val at = from + done + i
                data[at] = (data[at].toInt() xor stream[within + i].toInt()).toByte()
            }
            done += count
        }
    }

    private fun keystream(firstBlock: Long, blocks: Int): ByteArray {
        val counters = ByteArray(blocks * BLOCK)
        var low = ivLow + firstBlock
        var high = ivHigh + if (low.toULong() < ivLow.toULong()) 1 else 0
        for (i in 0 until blocks) {
            writeU64(counters, i * BLOCK, high)
            writeU64(counters, i * BLOCK + 8, low)
            low += 1
            if (low == 0L) high += 1
        }
        return cipher.doFinal(counters)
    }

    companion object {
        const val BLOCK = 16
        private const val MAX_BLOCKS = 4_096L
    }
}
