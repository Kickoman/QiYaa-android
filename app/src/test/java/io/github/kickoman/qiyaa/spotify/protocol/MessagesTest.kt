package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import io.github.kickoman.qiyaa.support.Vector
import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MessagesTest {
    @Test
    fun `every case of spec spotify messages json builds its body`() {
        SpotifyVectors.forEachCase("messages") { case ->
            when (val kind = case.text("kind")) {
                "hashcash" -> checkHashcash(case)

                "login5" -> assertEquals(case.text("body"), login5(case).toHex())

                "clientToken" -> assertEquals(case.text("body"), clientToken(case).toHex())

                "clientTokenAnswer" -> assertEquals(
                    case.text("body"),
                    Login.clientTokenAnswer(case.text("state"), Bytes.of(case.hex("suffix"))).toHex(),
                )

                "metadata" -> {
                    val extensionKind = ExtensionKind.entries.single { it.code == case.int("extensionKind") }
                    assertEquals(
                        case.text("body"),
                        Metadata.request(case.texts("uris"), extensionKind).toHex(),
                    )
                }

                "autoplay" -> assertEquals(
                    case.text("body"),
                    CatalogMessages.autoplayRequest(
                        case.text("contextUri"),
                        case.texts("recentTrackUris"),
                    ).toHex(),
                )

                "collection" -> assertEquals(
                    case.text("body"),
                    CatalogMessages.collectionPageRequest(
                        case.text("username"),
                        case.text("set"),
                        case.text("pageToken"),
                        case.int("limit"),
                    ).toHex(),
                )

                else -> fail("unknown kind $kind")
            }
        }
    }

    private fun checkHashcash(case: Vector) {
        val context = case.hex("context")
        val prefix = case.hex("prefix")
        val length = case.int("length")
        val suffix = case.text("suffix")
        val solved = Hashcash.solve(Bytes.of(context), HashcashChallenge(Bytes.of(prefix), length)) { false }
        assertEquals(suffix, solved?.hex())
        assertEquals(
            "the first suffix by the README's definition",
            suffix,
            firstSolution(context, prefix, length),
        )
    }

    private fun firstSolution(context: ByteArray, prefix: ByteArray, length: Int): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val target = BigInteger(1, sha1.digest(context).copyOfRange(12, 20))
        val wrap = BigInteger.ONE.shiftLeft(64)
        var n = BigInteger.ZERO
        while (true) {
            val suffix = eightBytes(target.add(n).mod(wrap)) + eightBytes(n)
            val tail = BigInteger(1, sha1.digest(prefix + suffix).copyOfRange(12, 20))
            if (tail.signum() == 0 || tail.lowestSetBit >= length) return suffix.toHex()
            n = n.add(BigInteger.ONE)
        }
    }

    private fun eightBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().takeLast(8).toByteArray()
        return ByteArray(8 - bytes.size) + bytes
    }

    private fun login5(case: Vector): ByteArray {
        val credentials = Credentials(
            case.text("username"),
            AuthenticationType.STORED_CREDENTIALS,
            Bytes.of(case.hex("data")),
        )
        val solution = case.objOrNull("hashcash")?.let {
            HashcashSolution(Bytes.of(it.hex("suffix")), it.long("tookNanos"))
        }
        return Login.login5Request(
            case.text("clientId"),
            case.text("deviceId"),
            credentials,
            Bytes.of(case.hex("context")),
            solution,
        )
    }

    private fun clientToken(case: Vector): ByteArray {
        val platform = case.obj("platform")
        val data = when (val os = platform.text("os")) {
            "linux" -> ClientPlatform.Linux(
                platform.text("release"),
                platform.text("version"),
                platform.text("hardware"),
            )

            "windows" -> ClientPlatform.Windows(
                platform.int("osVersion"),
                platform.int("osBuild"),
                platform.int("imageFileMachine"),
                platform.int("peMachine"),
            )

            "macos" -> ClientPlatform.Mac(platform.text("systemVersion"), platform.text("cpuType"))

            else -> throw AssertionError("unknown os $os")
        }
        val params =
            ClientTokenParams(case.text("clientVersion"), case.text("clientId"), case.text("deviceId"), data)
        return Login.clientTokenRequest(params)
    }
}
