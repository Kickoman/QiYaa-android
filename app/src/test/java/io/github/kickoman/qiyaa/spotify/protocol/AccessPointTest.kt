package io.github.kickoman.qiyaa.spotify.protocol

import io.github.kickoman.qiyaa.support.SpotifyVectors
import io.github.kickoman.qiyaa.support.Vector
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessPointTest {
    @Test
    fun `every session of spec spotify handshake json replays and agrees with the fake access point`() {
        SpotifyVectors.forEachCase("handshake") { case ->
            replay(case, byteByByte = false)
            replay(case, byteByByte = true)
            checkAccessPointSide(case)
        }
    }

    @Test
    fun `bytes before start are ignored`() {
        val case = session(0)
        val client = client(case)
        client.receive(byteArrayOf(0, 0, 0, 8, 1, 2, 3, 4), 0)
        assertEquals(0, client.takeOutgoing().size)
        assertEquals(emptyList<ApEvent>(), client.takeEvents())
        assertFalse(client.failed)
        client.start()
        assertEquals(case.list("steps")[0].list("outgoing")[0].text("wire"), client.takeOutgoing().toHex())
    }

    @Test
    fun `a handshake reply shorter than its length field or longer than 64 KiB ends the session`() {
        for (size in listOf(3, AccessPoint.MAX_HANDSHAKE_PACKET + 1)) {
            val client = client(session(0)).apply { start() }
            client.takeOutgoing()
            client.receive(u32(size), 0)
            val reason = "bad handshake packet size $size, not 4..${AccessPoint.MAX_HANDSHAKE_PACKET}"
            assertEquals(listOf(ApEvent.Failure(reason)), client.takeEvents())
            assertTrue(client.failed)
        }
        val waiting = client(session(0)).apply { start() }
        waiting.receive(u32(AccessPoint.MAX_HANDSHAKE_PACKET), 0)
        assertEquals(emptyList<ApEvent>(), waiting.takeEvents())
        assertFalse(waiting.failed)
    }

    @Test
    fun `no audio key is asked before the login`() {
        val client = client(session(0)).apply { start() }
        client.takeOutgoing()
        assertNull(
            client.requestAudioKey(Bytes.of(ByteArray(Ids.GID_SIZE)), Bytes.of(ByteArray(Ids.FILE_ID_SIZE))),
        )
        assertEquals(0, client.takeOutgoing().size)
    }

    @Test
    fun `a packet whose MAC does not match ends the session`() {
        val case = session(0)
        val client = replayedUpTo(case, steps = 2)
        val welcome = case.list("steps")[2].list("incoming")[0].hex("wire")
        welcome[welcome.size - 1] = (welcome.last().toInt() xor 1).toByte()
        client.receive(welcome, 1_000)
        val size = welcome.size - 3 - Shannon.MAC_SIZE
        assertEquals(
            listOf(ApEvent.Failure("packet authentication failed: command 0xac, nonce 0, $size bytes")),
            client.takeEvents(),
        )
        assertTrue(client.failed)
        assertNull(client.deadline())
    }

    @Test
    fun `an APLoginFailed in the handshake reply that breaks off counts as absent`() {
        val case = session(0)
        val client = client(case).apply { start() }
        client.takeOutgoing()
        val response = case.list("steps")[1].list("incoming")[0].hex("wire")
        val body = response.copyOfRange(4, response.size) + byteArrayOf(0xf2.toByte(), 0x01, 0x01, 0x50)
        client.receive(u32(4 + body.size) + body, 0)
        assertEquals(emptyList<ApEvent>(), client.takeEvents())
        assertFalse(client.failed)
        assertTrue(client.takeOutgoing().isNotEmpty())
    }

    @Test
    fun `an AuthFailure packet that breaks off is a refusal with code 0`() {
        val case = session(0)
        val client = replayedUpTo(case, steps = 2)
        val serverToClient = case.obj("keys").hex("serverToClient")
        client.receive(packet(serverToClient, 0, 0xad, byteArrayOf(0x50)), 1_000)
        assertEquals(listOf(ApEvent.LoginFailed(LoginError.PROTOCOL_ERROR, "")), client.takeEvents())
        assertTrue(client.failed)
    }

    @Test
    fun `SPOT-18 a missed PongAck ends the session instead of sending another Pong`() {
        val case = session(0)
        val steps = case.list("steps")
        val pong = steps.indexOfFirst { step -> step.list("outgoing").any { it.text("what") == "Pong" } }
        val client = replayedUpTo(case, steps = pong + 1)
        client.takeEvents()
        val pongAt = steps[pong].long("nowMs")
        assertEquals(pongAt + AccessPoint.PONG_ACK_MS, client.deadline())
        client.advance(pongAt + AccessPoint.PONG_ACK_MS - 1)
        assertEquals(emptyList<ApEvent>(), client.takeEvents())
        client.advance(pongAt + AccessPoint.PONG_ACK_MS)
        val due = pongAt + AccessPoint.PONG_ACK_MS
        assertEquals(
            listOf(ApEvent.Failure("the access point stopped answering: no PongAck by $due ms, now $due ms")),
            client.takeEvents(),
        )
        assertEquals(0, client.takeOutgoing().size)
        assertTrue(client.failed)
        assertNull(client.deadline())
    }

    @Test
    fun `an AesKey of another length and an AesKeyError under six bytes are ignored`() {
        val case = session(0)
        val client = replayedUpTo(case, steps = 3)
        client.takeEvents()
        val serverToClient = case.obj("keys").hex("serverToClient")
        val nextNonce = case.list("steps")[2].list("incoming").size
        val packets = listOf(
            packet(serverToClient, nextNonce, 0x0d, ByteArray(21) { 7 }),
            packet(serverToClient, nextNonce + 1, 0x0e, byteArrayOf(0, 0, 0, 1, 0)),
            packet(serverToClient, nextNonce + 2, 0x0d, byteArrayOf(0, 0, 0, 5) + ByteArray(16) { 7 }),
        )
        client.receive(packets.reduce(ByteArray::plus), 2_000)
        assertEquals(
            listOf(ApEvent.AudioKey(5, Bytes.of(ByteArray(16) { 7 }))),
            client.takeEvents(),
        )
        assertTrue(client.loggedIn)
    }

    @Test
    fun `a signed access point key not below the prime is refused, the prime minus one is taken`() {
        val prime = AccessPoint.DH_PRIME.toByteArray()
        val primeMinusOne = BigInteger(1, prime).subtract(BigInteger.ONE).toByteArray().takeLast(prime.size)
        val signer = SpotifyVectors.cases("crypto").first { it.has("privateExponent") }
        val modulus = signer.hex("modulus")
        val key = ServerKey(Bytes.of(modulus), signer.int("exponent"))
        for ((gs, refused) in listOf(prime to true, primeMinusOne.toByteArray() to false)) {
            val client = AccessPoint(identity(session(0).obj("identity")), CREDENTIALS, ::counting, key)
            client.start()
            client.takeOutgoing()
            val signature = sign(Crypto.sha1(gs), modulus, signer.hex("privateExponent"))
            client.receive(apResponse(gs, signature), 0)
            assertEquals(refused, client.failed)
            assertEquals(refused, client.takeOutgoing().isEmpty())
            val reason = "the access point's key of ${gs.size} bytes is not below the prime"
            assertEquals(if (refused) listOf(ApEvent.Failure(reason)) else emptyList(), client.takeEvents())
        }
    }

    private fun replay(case: Vector, byteByByte: Boolean) {
        val randoms = ArrayDeque(case.hexes("random"))
        val client = AccessPoint(
            identity(case.obj("identity")),
            credentials(case.obj("credentials")),
            { bytes ->
                val next = randoms.removeFirst()
                assertEquals("the size of a random draw", next.size, bytes.size)
                next.copyInto(bytes)
            },
            serverKey(case.obj("trustedKey")),
        )
        for ((index, step) in case.list("steps").withIndex()) {
            val label = "step $index (${step.text("call")}, byte by byte: $byteByByte)"
            when (val call = step.text("call")) {
                "start" -> client.start()

                "receive" -> {
                    val nowMs = step.long("nowMs")
                    val incoming = step.list("incoming").map {
                        it.hex("wire")
                    }.fold(ByteArray(0), ByteArray::plus)
                    if (byteByByte) {
                        for (byte in incoming) client.receive(byteArrayOf(byte), nowMs)
                    } else {
                        client.receive(incoming, nowMs)
                    }
                }

                "advance" -> client.advance(step.long("nowMs"))

                "requestAudioKey" -> assertEquals(
                    label,
                    step.longOrNull("returns")?.toInt(),
                    client.requestAudioKey(Bytes.of(step.hex("track")), Bytes.of(step.hex("file"))),
                )

                else -> fail("unknown call $call")
            }
            val outgoing = step.list("outgoing").joinToString("") { it.text("wire") }
            assertEquals("$label: outgoing", outgoing, client.takeOutgoing().toHex())
            assertEquals(
                "$label: events",
                step.list("events").map(Vector::fields),
                client.takeEvents().map(::fieldsOf),
            )
            assertEquals("$label: deadline", step.longOrNull("deadline"), client.deadline())
            assertEquals("$label: loggedIn", step.bool("loggedIn"), client.loggedIn)
            assertEquals("$label: failed", step.bool("failed"), client.failed)
        }
        assertTrue("every random draw was used", randoms.isEmpty())
    }

    private fun checkAccessPointSide(case: Vector) {
        val prime = case.hex("dhPrime")
        assertEquals(AccessPoint.DH_PRIME.hex(), prime.toHex())
        val server = case.obj("server")
        val dhPublic = server.hex("dhPublic")
        assertEquals(dhPublic.toHex(), Crypto.modPow(byteArrayOf(2), server.hex("dhPrivate"), prime)?.toHex())
        val rsaModulus = server.hex("rsaModulus")
        val rsaExponent = server.int("rsaExponent")
        assertTrue(
            Crypto.verifyRsaSha1(rsaModulus, rsaExponent, Crypto.sha1(dhPublic), server.hex("signature")),
        )
        val trusted = serverKey(case.obj("trustedKey"))
        if (trusted != ServerKey(Bytes.of(rsaModulus), rsaExponent)) assertEquals(ServerKey.SPOTIFY, trusted)

        val messages = case.list("steps").flatMap { step ->
            step.list("outgoing").map { it to true } +
                if (step.has("incoming")) step.list("incoming").map { it to false } else emptyList()
        }
        for ((message, _) in messages) assertTrue(message.text("what"), message.text("what") in MESSAGE_NAMES)
        val hello = messages.first { it.first.text("what") == "ClientHello" }.first.hex("wire")
        assertEquals("0004", hello.copyOfRange(0, 2).toHex())
        assertEquals(hello.size, readU32(hello, 2))
        val keys = case.objOrNull("keys")
        if (keys == null) {
            assertTrue(messages.none { (message, _) -> message.has("nonce") })
            return
        }
        val clientPublic = fieldAt(hello.copyOfRange(6, hello.size), 50, 10, 10)
        assertEquals(keys.text("clientPublic"), clientPublic.toHex())
        val random = case.hexes("random")
        assertEquals(
            clientPublic.toHex(),
            stripLeadingZeros(Crypto.modPow(byteArrayOf(2), random[0], prime)!!).toHex(),
        )
        val shared = stripLeadingZeros(Crypto.modPow(clientPublic, server.hex("dhPrivate"), prime)!!)
        assertEquals(keys.text("shared"), shared.toHex())
        assertEquals(shared.toHex(), stripLeadingZeros(Crypto.modPow(dhPublic, random[0], prime)!!).toHex())

        val response = messages.first { it.first.text("what") == "APResponseMessage" }.first.hex("wire")
        val transcript = hello + response
        val material = (1..5).map { Crypto.hmacSha1(shared, transcript, byteArrayOf(it.toByte())) }
            .reduce(ByteArray::plus)
        assertEquals(keys.text("material"), material.toHex())
        val answer = Crypto.hmacSha1(material.copyOfRange(0, 20), transcript)
        assertEquals(keys.text("challengeAnswer"), answer.toHex())
        val plaintext = messages.first {
            it.first.text("what") == "ClientResponsePlaintext"
        }.first.hex("wire")
        assertEquals(answer.toHex(), fieldAt(plaintext.copyOfRange(4, plaintext.size), 10, 10, 10).toHex())
        val clientToServer = keys.hex("clientToServer")
        val serverToClient = keys.hex("serverToClient")
        assertEquals(material.copyOfRange(20, 52).toHex(), clientToServer.toHex())
        assertEquals(material.copyOfRange(52, 84).toHex(), serverToClient.toHex())
        for ((message, fromClient) in messages.filter { it.first.has("nonce") }) {
            checkPacket(message, if (fromClient) clientToServer else serverToClient)
        }
    }

    private fun checkPacket(message: Vector, key: ByteArray) {
        val wire = message.hex("wire")
        val payload = message.hex("payload")
        val command = message.text("command")
        assertEquals(message.text("what"), COMMAND_NAMES[command.toInt(16)])
        val cipher = Shannon(key)
        cipher.nonce(message.int("nonce"))
        val plain = wire.copyOfRange(0, wire.size - Shannon.MAC_SIZE).also(cipher::decrypt)
        assertEquals(command + u16(payload.size).toHex() + payload.toHex(), plain.toHex())
        assertEquals(
            wire.copyOfRange(wire.size - Shannon.MAC_SIZE, wire.size).toHex(),
            cipher.finish().toHex(),
        )
    }

    private fun replayedUpTo(case: Vector, steps: Int): AccessPoint {
        val client = client(case)
        for (step in case.list("steps").take(steps)) {
            when (val call = step.text("call")) {
                "start" -> client.start()

                "receive" -> client.receive(
                    step.list("incoming").map { it.hex("wire") }.fold(ByteArray(0), ByteArray::plus),
                    step.long("nowMs"),
                )

                "advance" -> client.advance(step.long("nowMs"))

                "requestAudioKey" -> client.requestAudioKey(
                    Bytes.of(step.hex("track")),
                    Bytes.of(step.hex("file")),
                )

                else -> fail("unknown call $call")
            }
            client.takeOutgoing()
        }
        return client
    }

    private fun client(case: Vector): AccessPoint {
        val randoms = ArrayDeque(case.hexes("random"))
        return AccessPoint(
            identity(case.obj("identity")),
            credentials(case.obj("credentials")),
            { randoms.removeFirst().copyInto(it) },
            serverKey(case.obj("trustedKey")),
        )
    }

    private fun session(index: Int): Vector = SpotifyVectors.cases("handshake")[index]

    private fun identity(identity: Vector) = ClientIdentity(
        platform = ApPlatform.entries.single { it.code == identity.int("platform") },
        cpuFamily = ApCpuFamily.entries.single { it.code == identity.int("cpuFamily") },
        os = ApOs.entries.single { it.code == identity.int("os") },
        systemInformation = identity.text("systemInformation"),
        versionString = identity.text("versionString"),
        deviceId = identity.text("deviceId"),
        protocolVersion = identity.long("protocolVersion"),
    )

    private fun credentials(credentials: Vector) = Credentials(
        credentials.text("username"),
        AuthenticationType(credentials.int("type")),
        Bytes.of(credentials.hex("data")),
    )

    private fun serverKey(key: Vector) = ServerKey(Bytes.of(key.hex("modulus")), key.int("exponent"))

    private fun fieldsOf(event: ApEvent): Map<String, String> = when (event) {
        is ApEvent.LoggedIn -> mapOf(
            "event" to "loggedIn",
            "username" to event.reusable.username,
            "type" to event.reusable.type.code.toString(),
            "data" to event.reusable.data.hex(),
        )

        is ApEvent.LoginFailed -> mapOf(
            "event" to "loginFailed",
            "code" to event.error.code.toString(),
            "description" to event.description,
        )

        is ApEvent.Country -> mapOf("event" to "country", "code" to event.code)

        is ApEvent.Product -> mapOf("event" to "product", "type" to event.type)

        is ApEvent.AudioKey -> mapOf(
            "event" to "audioKey",
            "sequence" to Integer.toUnsignedString(event.sequence),
            "key" to event.key.hex(),
        )

        is ApEvent.AudioKeyRefused -> mapOf(
            "event" to "audioKeyRefused",
            "sequence" to Integer.toUnsignedString(event.sequence),
            "code" to event.code.toString(),
        )

        is ApEvent.Failure -> mapOf("event" to "failure")
    }

    private fun fieldAt(message: ByteArray, vararg path: Int): ByteArray {
        var bytes = message
        for (field in path) {
            val reader = ProtoReader(bytes)
            var found: ByteArray? = null
            while (found == null && reader.next()) {
                if (reader.field == field) found = reader.bytes()
            }
            bytes = found ?: throw AssertionError("no field $field on the path ${path.toList()}")
        }
        return bytes
    }

    private fun packet(key: ByteArray, nonce: Int, command: Int, payload: ByteArray): ByteArray {
        val cipher = Shannon(key)
        cipher.nonce(nonce)
        val packet = byteArrayOf(command.toByte()) + u16(payload.size) + payload
        cipher.encrypt(packet)
        return packet + cipher.finish()
    }

    private fun apResponse(gs: ByteArray, signature: ByteArray): ByteArray {
        val dh = ProtoWriter().bytes(10, gs).varint(20, 1).bytes(30, signature)
        val challenge = ProtoWriter().message(10, ProtoWriter().message(10, dh))
        val response = ProtoWriter().message(10, challenge).toByteArray()
        return u32(4 + response.size) + response
    }

    private fun sign(digest: ByteArray, modulus: ByteArray, privateExponent: ByteArray): ByteArray {
        val digestInfo = checkNotNull(bytesOfHex("3021300906052b0e03021a05000414"))
        val padding = modulus.size - 3 - digestInfo.size - digest.size
        val encoded = byteArrayOf(0, 1) + ByteArray(padding) { -1 } + byteArrayOf(0) + digestInfo + digest
        val n = BigInteger(1, modulus)
        val signature = BigInteger(1, encoded).modPow(BigInteger(1, privateExponent), n).toByteArray()
        return signature.takeLast(modulus.size).toByteArray()
    }

    private fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Int): ByteArray = ByteArrayOutputStream().apply { writeU32(value) }.toByteArray()

    private companion object {
        val CREDENTIALS =
            Credentials("owner", AuthenticationType.STORED_CREDENTIALS, Bytes.of(byteArrayOf(1, 2, 3, 4)))
        val COMMAND_NAMES = mapOf(
            0x04 to "Ping",
            0x0c to "RequestKey",
            0x0d to "AesKey",
            0x0e to "AesKeyError",
            0x1b to "CountryCode",
            0x49 to "Pong",
            0x4a to "PongAck",
            0x50 to "ProductInfo",
            0x76 to "LicenseVersion",
            0xab to "Login",
            0xac to "APWelcome",
            0xad to "AuthFailure",
        )
        val MESSAGE_NAMES =
            COMMAND_NAMES.values + listOf("ClientHello", "APResponseMessage", "ClientResponsePlaintext")

        fun counting(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (i * 13 + 5).toByte()
        }
    }
}
