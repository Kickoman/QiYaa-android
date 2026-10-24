package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import io.github.kickoman.qiyaa.support.Vector
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    @Test
    fun `every case of spec spotify crypto json gives its result`() {
        SpotifyVectors.forEachCase("crypto") { case ->
            when (val kind = case.text("kind")) {
                "sha1" -> assertEquals(case.text("digest"), Crypto.sha1(case.hex("message")).toHex())

                "hmac-sha1" -> assertEquals(
                    case.text("digest"),
                    Crypto.hmacSha1(case.hex("key"), case.hex("message")).toHex(),
                )

                "aes-128-ctr" -> checkAesCtr(case)

                "modpow" -> assertEquals(
                    case.textOrNull("result"),
                    Crypto.modPow(case.hex("base"), case.hex("exponent"), case.hex("modulus"))?.toHex(),
                )

                "dh" -> checkDiffieHellman(case)

                "rsa-pkcs1-sha1" -> checkRsa(case)

                else -> fail("unknown kind $kind")
            }
        }
    }

    @Test
    fun `an empty HMAC key is the all-zero key, as BearSSL takes it`() {
        assertEquals("fbdb1d1b18aa6c08324b7d64b71fb76370690e1d", Crypto.hmacSha1(ByteArray(0)).toHex())
        assertEquals(
            Crypto.hmacSha1(ByteArray(1), MESSAGE).toHex(),
            Crypto.hmacSha1(ByteArray(0), MESSAGE).toHex(),
        )
    }

    @Test
    fun `modPow refuses an empty modulus and one of more than 512 bytes, as the desktop does`() {
        assertNull(Crypto.modPow(byteArrayOf(1), byteArrayOf(1), ByteArray(0)))
        assertNull(Crypto.modPow(byteArrayOf(1), byteArrayOf(1), ByteArray(513) { -1 }))
        assertEquals(512, Crypto.modPow(byteArrayOf(1), byteArrayOf(1), ByteArray(512) { -1 })?.size)
    }

    @Test
    fun `a signature longer than the modulus is refused even when its value is below it`() {
        val case = SpotifyVectors.cases("crypto").first {
            it.text("kind") == "rsa-pkcs1-sha1" &&
                it.bool("valid")
        }
        val modulus = case.hex("modulus")
        val exponent = case.int("exponent")
        val digest = Crypto.sha1(case.hex("message"))
        val signature = case.hex("signature")
        assertTrue(Crypto.verifyRsaSha1(modulus, exponent, digest, signature))
        assertFalse(Crypto.verifyRsaSha1(modulus, exponent, digest, byteArrayOf(0) + signature))
    }

    @Test
    fun `AES-CTR over more blocks than one batch gives the same bytes as piece by piece`() {
        val key = ByteArray(16) { 3 }
        val iv = ByteArray(16) { if (it < 12) 0 else -1 }
        val whole = ByteArray(200_000) { it.toByte() }
        val pieces = whole.copyOf()
        val cipher = AesCtr(key, iv)
        cipher.apply(whole, 12_345)
        for (from in pieces.indices step 997) {
            val length = minOf(997, pieces.size - from)
            cipher.apply(pieces, 12_345L + from, from, length)
        }
        assertArrayEquals(whole, pieces)
    }

    private fun checkAesCtr(case: Vector) {
        val cipher = AesCtr(case.hex("key"), case.hex("iv"))
        val offset = case.long("offset")
        val input = case.hex("input")
        val output = case.text("output")
        assertEquals(output, input.copyOf().also { cipher.apply(it, offset) }.toHex())
        val byteByByte = input.copyOf()
        for (i in byteByByte.indices) cipher.apply(byteByByte, offset + i, i, 1)
        assertEquals(output, byteByByte.toHex())
        val framed = byteArrayOf(0x55, 0x55, 0x55) + input + byteArrayOf(0x66)
        cipher.apply(framed, offset, 3, input.size)
        assertEquals("555555" + output + "66", framed.toHex())
    }

    private fun checkDiffieHellman(case: Vector) {
        val prime = case.hex("prime")
        val generator = case.hex("generator")
        val privateA = case.hex("privateA")
        val privateB = case.hex("privateB")
        val publicA = Crypto.modPow(generator, privateA, prime)
        val publicB = Crypto.modPow(generator, privateB, prime)
        assertEquals(case.text("publicA"), publicA?.toHex())
        assertEquals(case.text("publicB"), publicB?.toHex())
        val shared = case.text("shared")
        assertEquals(shared, Crypto.modPow(checkNotNull(publicB), privateA, prime)?.toHex())
        assertEquals(shared, Crypto.modPow(checkNotNull(publicA), privateB, prime)?.toHex())
        assertEquals(prime.size * 2, shared.length)
    }

    private fun checkRsa(case: Vector) {
        val modulus = case.hex("modulus")
        val exponent = case.int("exponent")
        val signature = case.hex("signature")
        val encoded = case.hexOrNull("encoded")
        assertEquals(encoded?.toHex(), Crypto.rsaPublic(modulus, exponent, signature)?.toHex())
        val digest = Crypto.sha1(case.hex("message"))
        assertEquals(case.bool("valid"), Crypto.verifyRsaSha1(modulus, exponent, digest, signature))
        if (case.has("privateExponent")) {
            val privateExponent = BigInteger(1, case.hex("privateExponent"))
            if (encoded != null) {
                val signed = BigInteger(1, encoded).modPow(privateExponent, BigInteger(1, modulus))
                assertEquals(BigInteger(1, signature), signed)
            }
        }
    }

    private companion object {
        val MESSAGE = "message".toByteArray()
    }
}
