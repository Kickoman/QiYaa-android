package io.github.kickoman.qiyaa.spotify.protocol

import java.io.ByteArrayOutputStream

enum class ApPlatform(val code: Int) {
    WIN32_X86(0x00),
    OSX_X86(0x01),
    LINUX_X86(0x02),
    LINUX_X86_64(0x08),
    OSX_X86_64(0x09),
    LINUX_ARM(0x11),
    WIN32_X86_64(0x27),
}

enum class ApCpuFamily(val code: Int) {
    UNKNOWN(0),
    X86(1),
    X86_64(2),
    ARM(5),
}

enum class ApOs(val code: Int) {
    UNKNOWN(0),
    WINDOWS(1),
    OSX(2),
    LINUX(5),
    ANDROID(7),
}

data class ClientIdentity(
    val platform: ApPlatform,
    val cpuFamily: ApCpuFamily,
    val os: ApOs,
    val systemInformation: String,
    val versionString: String,
    val deviceId: String,
    val protocolVersion: Long = PROTOCOL_VERSION,
) {
    companion object {
        const val PROTOCOL_VERSION = 124_200_290L
    }
}

@JvmInline
value class AuthenticationType(val code: Int) {
    companion object {
        val STORED_CREDENTIALS = AuthenticationType(1)
        val SPOTIFY_TOKEN = AuthenticationType(3)
    }
}

data class Credentials(val username: String, val type: AuthenticationType, val data: Bytes) {
    companion object {
        fun fromAccessToken(token: String): Credentials =
            Credentials("", AuthenticationType.SPOTIFY_TOKEN, Bytes.of(token.toByteArray(Charsets.UTF_8)))
    }
}

data class ServerKey(val modulus: Bytes, val exponent: Int = 65_537) {
    companion object {
        val SPOTIFY = ServerKey(
            Bytes.ofHex(
                "ace0460bffc230aff46bfec3bfbf863da191c6cc336c93a14fb3b01612acac6af180e7f614d9429d" +
                    "be2e346643e362d2327a1a0d923baedd1402b18155056104d52c96a44c1ecc024ad4b20c001f17ed" +
                    "c22fc43521c8f0cbaed2add72b0f9db3c5321a2afe59f35a0dac68f1fa621efb2c8d0cb7392d9247" +
                    "e3d7351a6dbd24c2ae255b88ffab73298a0bcccd0c58673189e8bd3480784a5fc96b899d956bfc86" +
                    "d74f33a6781796c9c32d0d32a5abcd0527e2f710a39613c42f99c027bfed049c3c275804b6b219f9" +
                    "c12f02e94863eca1b642a09d4825f8b39dd0e86af9484da1c2ba863042ea9db3086c190e48b39d66" +
                    "eb0006a25aeea11b13873cd719e655bd",
            )!!,
        )
    }
}

@JvmInline
value class LoginError(val code: Int) {
    companion object {
        val PROTOCOL_ERROR = LoginError(0)
        val TRY_ANOTHER_AP = LoginError(2)
        val BAD_CONNECTION_ID = LoginError(5)
        val TRAVEL_RESTRICTION = LoginError(9)
        val PREMIUM_ACCOUNT_REQUIRED = LoginError(11)
        val BAD_CREDENTIALS = LoginError(12)
        val COULD_NOT_VALIDATE_CREDENTIALS = LoginError(13)
        val ACCOUNT_EXISTS = LoginError(14)
        val EXTRA_VERIFICATION_REQUIRED = LoginError(15)
        val INVALID_APP_KEY = LoginError(16)
        val APPLICATION_BANNED = LoginError(17)
    }
}

sealed interface ApEvent {
    data class LoggedIn(val reusable: Credentials) : ApEvent

    data class LoginFailed(val error: LoginError, val description: String) : ApEvent

    data class Country(val code: String) : ApEvent

    data class Product(val type: String) : ApEvent

    data class AudioKey(val sequence: Int, val key: Bytes) : ApEvent

    data class AudioKeyRefused(val sequence: Int, val code: Int) : ApEvent

    data class Failure(val reason: String) : ApEvent
}

class AccessPoint(
    private val identity: ClientIdentity,
    private val credentials: Credentials,
    private val random: (ByteArray) -> Unit,
    private val serverKey: ServerKey = ServerKey.SPOTIFY,
) {
    private enum class Stage { IDLE, HANDSHAKE, LOGIN, READY, FAILED }

    private enum class KeepAlive { EXPECTING_PING, PENDING_PONG, EXPECTING_PONG_ACK }

    private var stage = Stage.IDLE
    private var keepAlive = KeepAlive.EXPECTING_PING
    private var keepAliveDeadline: Long? = null
    private val outgoing = ByteArrayOutputStream()
    private var incoming = ByteArray(INITIAL_BUFFER)
    private var incomingSize = 0
    private var transcript = ByteArray(0)
    private var dhPrivate = ByteArray(0)
    private val events = mutableListOf<ApEvent>()
    private var sendCipher: Shannon? = null
    private var receiveCipher: Shannon? = null
    private var sendNonce = 0
    private var receiveNonce = 0
    private var pendingCommand = NO_COMMAND
    private var pendingSize = 0
    private var nextKeySequence = 0

    val loggedIn: Boolean get() = stage == Stage.READY

    val failed: Boolean get() = stage == Stage.FAILED

    fun start() {
        check(stage == Stage.IDLE) { "start() once per connection, the session is $stage" }
        dhPrivate = ByteArray(DH_PRIVATE_SIZE).also(random)
        val gc = Crypto.modPow(byteArrayOf(GENERATOR), dhPrivate, DH_PRIME.toByteArray())
            ?: return fail("could not compute the key exchange")
        val nonce = ByteArray(NONCE_SIZE).also(random)
        val buildInfo = ProtoWriter()
            .varint(BuildInfoFields.PRODUCT, 0)
            .varint(BuildInfoFields.PRODUCT_FLAGS, 0)
            .varint(BuildInfoFields.PLATFORM, identity.platform.code.toLong())
            .varint(BuildInfoFields.VERSION, identity.protocolVersion)
        val dhHello = ProtoWriter()
            .bytes(DhHelloFields.GC, stripLeadingZeros(gc))
            .varint(DhHelloFields.SERVER_KEYS_KNOWN, 1)
        val cryptoHello = ProtoWriter().message(CryptoUnionFields.DIFFIE_HELLMAN, dhHello)
        val hello = ProtoWriter()
            .message(ClientHelloFields.BUILD_INFO, buildInfo)
            .varint(ClientHelloFields.CRYPTOSUITES_SUPPORTED, 0)
            .message(ClientHelloFields.LOGIN_CRYPTO_HELLO, cryptoHello)
            .bytes(ClientHelloFields.CLIENT_NONCE, nonce)
            .bytes(ClientHelloFields.PADDING, byteArrayOf(PADDING))
            .toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(HELLO_PREFIX)
        packet.writeU32(HELLO_PREFIX.size + 4 + hello.size)
        packet.write(hello)
        transcript = packet.toByteArray()
        outgoing.write(transcript)
        stage = Stage.HANDSHAKE
    }

    fun receive(bytes: ByteArray, nowMs: Long) {
        if (stage == Stage.FAILED || stage == Stage.IDLE) return
        append(bytes)
        if (stage == Stage.HANDSHAKE) readHandshake()
        if (stage == Stage.LOGIN || stage == Stage.READY) readPackets(nowMs)
    }

    fun advance(nowMs: Long) {
        val due = keepAliveDeadline
        if (stage != Stage.READY || due == null || nowMs < due) return
        if (keepAlive != KeepAlive.PENDING_PONG) return fail("the access point stopped answering")
        sendPacket(ApCommand.PONG, ByteArray(4))
        keepAlive = KeepAlive.EXPECTING_PONG_ACK
        keepAliveDeadline = nowMs + PONG_ACK_MS
    }

    fun deadline(): Long? = if (stage == Stage.READY) keepAliveDeadline else null

    fun takeOutgoing(): ByteArray = outgoing.toByteArray().also { outgoing.reset() }

    fun takeEvents(): List<ApEvent> = events.toList().also { events.clear() }

    fun requestAudioKey(track: Bytes, file: Bytes): Int? {
        require(track.size == Ids.GID_SIZE) { "a track gid is ${Ids.GID_SIZE} bytes, got ${track.size}" }
        require(file.size == Ids.FILE_ID_SIZE) { "a file id is ${Ids.FILE_ID_SIZE} bytes, got ${file.size}" }
        if (stage != Stage.READY) return null
        val sequence = nextKeySequence++
        val payload = ByteArrayOutputStream()
        payload.write(file.toByteArray())
        payload.write(track.toByteArray())
        payload.writeU32(sequence)
        payload.writeU16(0)
        sendPacket(ApCommand.REQUEST_KEY, payload.toByteArray())
        return sequence
    }

    private fun append(bytes: ByteArray) {
        if (incomingSize + bytes.size > incoming.size) {
            incoming = incoming.copyOf(maxOf(incoming.size * 2, incomingSize + bytes.size))
        }
        bytes.copyInto(incoming, incomingSize)
        incomingSize += bytes.size
    }

    private fun consume(count: Int) {
        incoming.copyInto(incoming, 0, count, incomingSize)
        incomingSize -= count
    }

    private fun readHandshake() {
        if (incomingSize < 4) return
        val size = readU32(incoming, 0).toLong() and 0xffffffffL
        if (size < 4 || size > MAX_HANDSHAKE_PACKET) return fail("bad handshake packet size")
        if (incomingSize < size) return
        val packet = incoming.copyOf(size.toInt())
        consume(size.toInt())
        transcript += packet
        answerChallenge(packet.copyOfRange(4, packet.size))
    }

    private fun answerChallenge(reply: ByteArray) {
        val challenge = parseApResponse(reply) ?: return fail("malformed handshake reply")
        challenge.failed?.let {
            events += it
            stage = Stage.FAILED
            return
        }
        if (challenge.gs.isEmpty() || challenge.signature.isEmpty()) return fail("handshake reply has no key")
        val modulus = serverKey.modulus.toByteArray()
        val digest = Crypto.sha1(challenge.gs)
        if (!Crypto.verifyRsaSha1(modulus, serverKey.exponent, digest, challenge.signature)) {
            return fail("the access point's key is not signed by Spotify")
        }
        val shared = Crypto.modPow(challenge.gs, dhPrivate, DH_PRIME.toByteArray())
            ?: return fail("bad key from the access point")
        val secret = stripLeadingZeros(shared)
        val material = ByteArrayOutputStream()
        for (i in 1..5) material.write(Crypto.hmacSha1(secret, transcript, byteArrayOf(i.toByte())))
        val keys = material.toByteArray()
        val answer = Crypto.hmacSha1(keys.copyOfRange(0, 20), transcript)
        sendCipher = Shannon(keys.copyOfRange(20, 52))
        receiveCipher = Shannon(keys.copyOfRange(52, 84))
        dhPrivate = ByteArray(0)
        transcript = ByteArray(0)

        val dhResponse = ProtoWriter().bytes(DhResponseFields.HMAC, answer)
        val cryptoResponse = ProtoWriter().message(CryptoUnionFields.DIFFIE_HELLMAN, dhResponse)
        val plaintext = ProtoWriter()
            .message(ResponseFields.LOGIN_CRYPTO_RESPONSE, cryptoResponse)
            .message(ResponseFields.POW_RESPONSE, ProtoWriter())
            .message(ResponseFields.CRYPTO_RESPONSE, ProtoWriter())
            .toByteArray()
        outgoing.writeU32(4 + plaintext.size)
        outgoing.write(plaintext)

        stage = Stage.LOGIN
        sendLogin()
    }

    private fun sendLogin() {
        val loginCredentials = ProtoWriter()
        if (credentials.username.isNotEmpty()) {
            loginCredentials.string(LoginCredentialsFields.USERNAME, credentials.username)
        }
        loginCredentials
            .varint(LoginCredentialsFields.TYPE, credentials.type.code.toLong())
            .bytes(LoginCredentialsFields.AUTH_DATA, credentials.data)
        val systemInfo = ProtoWriter()
            .varint(SystemInfoFields.CPU_FAMILY, identity.cpuFamily.code.toLong())
            .varint(SystemInfoFields.OS, identity.os.code.toLong())
            .string(SystemInfoFields.SYSTEM_INFORMATION, identity.systemInformation)
            .string(SystemInfoFields.DEVICE_ID, identity.deviceId)
        val login = ProtoWriter()
            .message(LoginFields.LOGIN_CREDENTIALS, loginCredentials)
            .message(LoginFields.SYSTEM_INFO, systemInfo)
            .string(LoginFields.VERSION_STRING, identity.versionString)
        sendPacket(ApCommand.LOGIN, login.toByteArray())
    }

    private fun sendPacket(command: Int, payload: ByteArray) {
        require(payload.size <= 0xffff) { "a packet carries at most 65535 bytes, got ${payload.size}" }
        val cipher = checkNotNull(sendCipher) { "no packets before the handshake" }
        val packet = ByteArray(3 + payload.size)
        packet[0] = command.toByte()
        packet[1] = (payload.size ushr 8).toByte()
        packet[2] = payload.size.toByte()
        payload.copyInto(packet, 3)
        cipher.nonce(sendNonce++)
        cipher.encrypt(packet)
        outgoing.write(packet)
        outgoing.write(cipher.finish())
    }

    private fun readPackets(nowMs: Long) {
        val cipher = checkNotNull(receiveCipher) { "no packets before the handshake" }
        var consumed = 0
        while (stage != Stage.FAILED) {
            val available = incomingSize - consumed
            if (pendingCommand == NO_COMMAND) {
                if (available < 3) break
                val header = incoming.copyOfRange(consumed, consumed + 3)
                cipher.nonce(receiveNonce++)
                cipher.decrypt(header)
                pendingCommand = header[0].toInt() and 0xff
                pendingSize = readU16(header, 1)
                consumed += 3
                continue
            }
            if (available < pendingSize + Shannon.MAC_SIZE) break
            cipher.decrypt(incoming, consumed, pendingSize)
            val mac = incoming.copyOfRange(consumed + pendingSize, consumed + pendingSize + Shannon.MAC_SIZE)
            if (!cipher.finish().contentEquals(mac)) {
                fail("packet authentication failed")
                break
            }
            val command = pendingCommand
            val payload = incoming.copyOfRange(consumed, consumed + pendingSize)
            consumed += pendingSize + Shannon.MAC_SIZE
            pendingCommand = NO_COMMAND
            handlePacket(command, payload, nowMs)
        }
        consume(consumed)
    }

    private fun handlePacket(command: Int, payload: ByteArray, nowMs: Long) {
        when (command) {
            ApCommand.AP_WELCOME -> {
                val welcome = parseWelcome(payload) ?: return fail("malformed welcome")
                stage = Stage.READY
                keepAlive = KeepAlive.EXPECTING_PING
                keepAliveDeadline = nowMs + FIRST_PING_MS
                events += welcome
            }

            ApCommand.AUTH_FAILURE -> {
                events += parseLoginFailed(ProtoReader(payload))
                    ?: ApEvent.LoginFailed(LoginError.PROTOCOL_ERROR, "")
                stage = Stage.FAILED
            }

            ApCommand.PING -> {
                keepAlive = KeepAlive.PENDING_PONG
                keepAliveDeadline = nowMs + PONG_DELAY_MS
            }

            ApCommand.PONG_ACK -> {
                keepAlive = KeepAlive.EXPECTING_PING
                keepAliveDeadline = nowMs + NEXT_PING_MS
            }

            ApCommand.PRODUCT_INFO -> events += ApEvent.Product(productType(String(payload, Charsets.UTF_8)))

            ApCommand.COUNTRY_CODE -> events += ApEvent.Country(String(payload, Charsets.UTF_8))

            ApCommand.AES_KEY -> if (payload.size == 4 + AES_KEY_SIZE) {
                val key = Bytes.of(payload.copyOfRange(4, payload.size))
                events += ApEvent.AudioKey(readU32(payload, 0), key)
            }

            ApCommand.AES_KEY_ERROR -> if (payload.size >= 6) {
                events += ApEvent.AudioKeyRefused(readU32(payload, 0), readU16(payload, 4))
            }
        }
    }

    private fun fail(reason: String) {
        stage = Stage.FAILED
        events += ApEvent.Failure(reason)
    }

    private class DhChallenge(val gs: ByteArray, val signature: ByteArray, val failed: ApEvent.LoginFailed?)

    private fun parseApResponse(reply: ByteArray): DhChallenge? {
        var gs = ByteArray(0)
        var signature = ByteArray(0)
        var failed: ApEvent.LoginFailed? = null
        val response = ProtoReader(reply)
        while (response.next()) {
            if (response.field == ApResponseFields.LOGIN_FAILED) {
                failed = parseLoginFailed(response.message())
                continue
            }
            if (response.field != ApResponseFields.CHALLENGE) continue
            val apChallenge = response.message()
            while (apChallenge.next()) {
                if (apChallenge.field != ApChallengeFields.LOGIN_CRYPTO_CHALLENGE) continue
                val cryptoUnion = apChallenge.message()
                while (cryptoUnion.next()) {
                    if (cryptoUnion.field != CryptoUnionFields.DIFFIE_HELLMAN) continue
                    val dh = cryptoUnion.message()
                    while (dh.next()) {
                        when (dh.field) {
                            DhChallengeFields.GS -> gs = dh.bytes()
                            DhChallengeFields.GS_SIGNATURE -> signature = dh.bytes()
                        }
                    }
                }
            }
        }
        if (response.failed) return null
        return DhChallenge(gs, signature, failed)
    }

    private fun parseLoginFailed(reader: ProtoReader): ApEvent.LoginFailed? {
        var error = LoginError.PROTOCOL_ERROR
        var description = ""
        while (reader.next()) {
            when (reader.field) {
                LoginFailedFields.ERROR_CODE -> error = LoginError(reader.varint.toInt())
                LoginFailedFields.ERROR_DESCRIPTION -> description = reader.string()
            }
        }
        if (reader.failed) return null
        return ApEvent.LoginFailed(error, description)
    }

    private fun parseWelcome(payload: ByteArray): ApEvent.LoggedIn? {
        var username = ""
        var type = AuthenticationType.STORED_CREDENTIALS
        var data = Bytes.EMPTY
        val reader = ProtoReader(payload)
        while (reader.next()) {
            when (reader.field) {
                WelcomeFields.CANONICAL_USERNAME -> username = reader.string()
                WelcomeFields.CREDENTIALS_TYPE -> type = AuthenticationType(reader.varint.toInt())
                WelcomeFields.CREDENTIALS -> data = Bytes.of(reader.bytes())
            }
        }
        if (reader.failed) return null
        return ApEvent.LoggedIn(Credentials(username, type, data))
    }

    private fun productType(xml: String): String {
        val start = xml.indexOf(TYPE_OPEN)
        if (start < 0) return ""
        val end = xml.indexOf(TYPE_CLOSE, start + TYPE_OPEN.length)
        if (end < 0) return ""
        return xml.substring(start + TYPE_OPEN.length, end)
    }

    companion object {
        const val FIRST_PING_MS = 20_000L
        const val PONG_DELAY_MS = 60_000L
        const val PONG_ACK_MS = 20_000L
        const val NEXT_PING_MS = 80_000L
        const val DH_PRIVATE_SIZE = 95
        const val NONCE_SIZE = 16
        const val AES_KEY_SIZE = 16
        const val MAX_HANDSHAKE_PACKET = 64 * 1024
        val DH_PRIME: Bytes = Bytes.ofHex(
            "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22" +
                "514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6" +
                "f44c42e9a63a3620ffffffffffffffff",
        )!!
        private const val GENERATOR: Byte = 2
        private const val PADDING: Byte = 0x1e
        private val HELLO_PREFIX = byteArrayOf(0x00, 0x04)
        private const val INITIAL_BUFFER = 1_024
        private const val NO_COMMAND = -1
        private const val TYPE_OPEN = "<type>"
        private const val TYPE_CLOSE = "</type>"
    }
}

private object ApCommand {
    const val PING = 0x04
    const val REQUEST_KEY = 0x0c
    const val AES_KEY = 0x0d
    const val AES_KEY_ERROR = 0x0e
    const val COUNTRY_CODE = 0x1b
    const val PONG = 0x49
    const val PONG_ACK = 0x4a
    const val PRODUCT_INFO = 0x50
    const val LOGIN = 0xab
    const val AP_WELCOME = 0xac
    const val AUTH_FAILURE = 0xad
}

private object ClientHelloFields {
    const val BUILD_INFO = 10
    const val CRYPTOSUITES_SUPPORTED = 30
    const val LOGIN_CRYPTO_HELLO = 50
    const val CLIENT_NONCE = 60
    const val PADDING = 70
}

private object BuildInfoFields {
    const val PRODUCT = 10
    const val PRODUCT_FLAGS = 20
    const val PLATFORM = 30
    const val VERSION = 40
}

private object CryptoUnionFields {
    const val DIFFIE_HELLMAN = 10
}

private object DhHelloFields {
    const val GC = 10
    const val SERVER_KEYS_KNOWN = 20
}

private object ApResponseFields {
    const val CHALLENGE = 10
    const val LOGIN_FAILED = 30
}

private object ApChallengeFields {
    const val LOGIN_CRYPTO_CHALLENGE = 10
}

private object DhChallengeFields {
    const val GS = 10
    const val GS_SIGNATURE = 30
}

private object LoginFailedFields {
    const val ERROR_CODE = 10
    const val ERROR_DESCRIPTION = 40
}

private object ResponseFields {
    const val LOGIN_CRYPTO_RESPONSE = 10
    const val POW_RESPONSE = 20
    const val CRYPTO_RESPONSE = 30
}

private object DhResponseFields {
    const val HMAC = 10
}

private object LoginFields {
    const val LOGIN_CREDENTIALS = 10
    const val SYSTEM_INFO = 50
    const val VERSION_STRING = 70
}

private object LoginCredentialsFields {
    const val USERNAME = 10
    const val TYPE = 20
    const val AUTH_DATA = 30
}

private object SystemInfoFields {
    const val CPU_FAMILY = 10
    const val OS = 60
    const val SYSTEM_INFORMATION = 90
    const val DEVICE_ID = 100
}

private object WelcomeFields {
    const val CANONICAL_USERNAME = 10
    const val CREDENTIALS_TYPE = 30
    const val CREDENTIALS = 40
}
