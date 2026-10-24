package io.github.kickoman.qiyaa.spotify.protocol

import org.junit.Assert.assertFalse
import org.junit.Test

class SecretsTest {
    @Test
    fun `no token, username, device id, challenge state, login context or key prints through toString`() {
        val blob = Bytes.of(SECRET.toByteArray())
        val credentials = Credentials("$SECRET-user", AuthenticationType.STORED_CREDENTIALS, blob)
        val hashcash = HashcashChallenge(blob, 10)
        val identity = ClientIdentity(ApPlatform.LINUX_ARM, ApCpuFamily.ARM, ApOs.LINUX, "Q", "Q", SECRET)
        val linux = ClientPlatform.Linux("6.1", "14", "aarch64")
        val values = listOf(
            blob,
            credentials,
            Credentials.fromAccessToken("$SECRET-oauth"),
            ApEvent.LoggedIn(credentials),
            ApEvent.AudioKey(1, blob),
            identity,
            ClientTokenParams("1.2.52.442", "client", "$SECRET-device", linux),
            ClientTokenReply.Granted("$SECRET-client-token", 1),
            ClientTokenReply.Challenge("$SECRET-state", hashcash),
            Login5Reply.Ok("$SECRET-user", "$SECRET-bearer", 3_600),
            Login5Reply.Challenge(blob, hashcash),
            HashcashSolution(blob, 1),
            Login.parseLogin5(
                ProtoWriter().message(1, ProtoWriter().string(1, "$SECRET-user").string(2, "$SECRET-bearer"))
                    .toByteArray(),
            ),
        )
        for (value in values) {
            val text = value.toString()
            assertFalse(text, text.contains(SECRET) || text.contains(blob.hex()))
        }
    }

    private companion object {
        const val SECRET = "s3cr3t"
    }
}
