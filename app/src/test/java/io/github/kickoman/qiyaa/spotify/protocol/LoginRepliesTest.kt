package io.github.kickoman.qiyaa.spotify.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRepliesTest {
    @Test
    fun `a granted client token carries the token and when to refresh it`() {
        val token = ProtoWriter().string(1, "client-token").varint(3, 1_209_600)
        val reply = ProtoWriter().varint(1, 1).message(2, token).toByteArray()
        assertEquals(ClientTokenReply.Granted("client-token", 1_209_600), Login.parseClientToken(reply))
    }

    @Test
    fun `a client-token challenge carries its state and the hashcash prefix read from hex`() {
        val parameters = ProtoWriter().varint(1, 10).string(2, "0123456789ABCDEF")
        val challenge = ProtoWriter().varint(1, 1).message(4, parameters)
        val challenges = ProtoWriter().string(1, "state-1").message(2, challenge)
        val reply = ProtoWriter().varint(1, 2).message(3, challenges).toByteArray()
        assertEquals(
            ClientTokenReply.Challenge("state-1", HashcashChallenge(Bytes.ofHex("0123456789abcdef")!!, 10)),
            Login.parseClientToken(reply),
        )
    }

    @Test
    fun `a client-token challenge whose prefix is not hex has no hashcash`() {
        val parameters = ProtoWriter().varint(1, 10).string(2, "not hex")
        val challenges = ProtoWriter().string(1, "state-1").message(2, ProtoWriter().message(4, parameters))
        val reply = ProtoWriter().message(3, challenges).toByteArray()
        assertEquals(ClientTokenReply.Challenge("state-1", null), Login.parseClientToken(reply))
    }

    @Test
    fun `a client-token reply without a token or a challenge is a failure`() {
        val emptyToken = ProtoWriter().message(2, ProtoWriter().string(1, "")).toByteArray()
        for (reply in listOf(ByteArray(0), emptyToken, byteArrayOf(0x12, 0x05, 0x0a))) {
            assertTrue(reply.toHex(), Login.parseClientToken(reply) is ClientTokenReply.Failure)
        }
    }

    @Test
    fun `a login5 reply with an access token is ok`() {
        val ok = ProtoWriter().string(1, "owner").string(2, "bearer-token").varint(4, 3_600)
        val reply = ProtoWriter().message(1, ok).toByteArray()
        assertEquals(Login5Reply.Ok("owner", "bearer-token", 3_600), Login.parseLogin5(reply))
    }

    @Test
    fun `a login5 error carries its code, even next to an ok without a token`() {
        val reply = ProtoWriter().message(1, ProtoWriter().string(1, "owner")).varint(2, 1).toByteArray()
        assertEquals(Login5Reply.Error(1), Login.parseLogin5(reply))
    }

    @Test
    fun `a login5 hashcash challenge carries the reply's context and the raw prefix`() {
        val hashcash = ProtoWriter().bytes(1, byteArrayOf(1, 2, 3)).varint(2, 14)
        val challenges = ProtoWriter().message(1, ProtoWriter().message(1, hashcash))
        val reply = ProtoWriter().message(3, challenges).bytes(5, byteArrayOf(9, 9)).toByteArray()
        assertEquals(
            Login5Reply.Challenge(
                Bytes.of(byteArrayOf(9, 9)),
                HashcashChallenge(Bytes.of(byteArrayOf(1, 2, 3)), 14),
            ),
            Login.parseLogin5(reply),
        )
    }

    @Test
    fun `a broken or empty login5 reply is a failure`() {
        assertTrue(Login.parseLogin5(byteArrayOf(0x0a, 0x05, 0x0a)) is Login5Reply.Failure)
        assertTrue(Login.parseLogin5(ByteArray(0)) is Login5Reply.Failure)
    }

    @Test
    fun `hashcash asks whether to give up before the first try and then every 65536 tries`() {
        val impossible = HashcashChallenge(Bytes.of(byteArrayOf(1)), 65)
        var asked = 0
        assertNull(Hashcash.solve(Bytes.EMPTY, impossible) { ++asked == 3 })
        assertEquals(3, asked)
        val easy = HashcashChallenge(Bytes.of(byteArrayOf(1)), 0)
        assertNull(Hashcash.solve(Bytes.EMPTY, easy) { true })
    }
}
