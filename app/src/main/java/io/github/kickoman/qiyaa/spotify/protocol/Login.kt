package io.github.kickoman.qiyaa.spotify.protocol

import java.security.MessageDigest

sealed interface ClientPlatform {
    data class Linux(val release: String, val version: String, val hardware: String) : ClientPlatform

    data class Windows(
        val osVersion: Int = 10,
        val osBuild: Int = 21_370,
        val imageFileMachine: Int = 34_404,
        val peMachine: Int = 34_404,
    ) : ClientPlatform

    data class Mac(val systemVersion: String, val cpuType: String) : ClientPlatform
}

data class ClientTokenParams(
    val clientVersion: String,
    val clientId: String,
    val deviceId: String,
    val platform: ClientPlatform,
) {
    override fun toString(): String = "ClientTokenParams(clientVersion=$clientVersion, clientId=$clientId, " +
        "deviceId=${hidden(deviceId)}, platform=$platform)"
}

data class HashcashChallenge(val prefix: Bytes, val length: Int)

data class HashcashSolution(val suffix: Bytes, val tookNanos: Long)

sealed interface ClientTokenReply {
    data class Granted(val token: String, val refreshAfterSeconds: Long) : ClientTokenReply {
        override fun toString(): String =
            "Granted(token=${hidden(token)}, refreshAfterSeconds=$refreshAfterSeconds)"
    }

    data class Challenge(val state: String, val hashcash: HashcashChallenge?) : ClientTokenReply {
        override fun toString(): String = "Challenge(state=${hidden(state)}, hashcash=$hashcash)"
    }

    data class Failure(val reason: String) : ClientTokenReply
}

sealed interface Login5Reply {
    data class Ok(val username: String, val accessToken: String, val expiresInSeconds: Long) : Login5Reply {
        override fun toString(): String =
            "Ok(username=${hidden(username)}, accessToken=${hidden(accessToken)}, " +
                "expiresInSeconds=$expiresInSeconds)"
    }

    data class Challenge(val context: Bytes, val hashcash: HashcashChallenge) : Login5Reply

    data class Error(val code: Int) : Login5Reply {
        val transient: Boolean get() = code in TRANSIENT_CODES

        companion object {
            const val TIMEOUT = 4
            const val TOO_MANY_ATTEMPTS = 6
            const val TRY_AGAIN_LATER = 8
            val TRANSIENT_CODES = setOf(TIMEOUT, TOO_MANY_ATTEMPTS, TRY_AGAIN_LATER)
        }
    }

    data class Failure(val reason: String) : Login5Reply
}

object Login {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val LINUX = "Linux"
    private const val MAC_MODEL = "iMac21,1"
    private const val REQUEST_CLIENT_DATA = 1L
    private const val REQUEST_CHALLENGE_ANSWERS = 2L
    private const val CHALLENGE_HASHCASH = 3L

    fun clientTokenRequest(params: ClientTokenParams): ByteArray {
        val platform = ProtoWriter()
        when (val data = params.platform) {
            is ClientPlatform.Linux -> platform.message(
                PlatformFields.DESKTOP_LINUX,
                ProtoWriter()
                    .string(LinuxFields.SYSTEM_NAME, LINUX)
                    .string(LinuxFields.SYSTEM_RELEASE, data.release)
                    .string(LinuxFields.SYSTEM_VERSION, data.version)
                    .string(LinuxFields.HARDWARE, data.hardware),
            )

            is ClientPlatform.Windows -> platform.message(
                PlatformFields.DESKTOP_WINDOWS,
                ProtoWriter()
                    .varint(WindowsFields.OS_VERSION, data.osVersion.toLong())
                    .varint(WindowsFields.OS_BUILD, data.osBuild.toLong())
                    .varint(WindowsFields.PLATFORM_ID, 2)
                    .varint(WindowsFields.UNKNOWN_6, 9)
                    .varint(WindowsFields.IMAGE_FILE_MACHINE, data.imageFileMachine.toLong())
                    .varint(WindowsFields.PE_MACHINE, data.peMachine.toLong())
                    .varint(WindowsFields.UNKNOWN_10, 1),
            )

            is ClientPlatform.Mac -> platform.message(
                PlatformFields.DESKTOP_MACOS,
                ProtoWriter()
                    .string(MacFields.SYSTEM_VERSION, data.systemVersion)
                    .string(MacFields.HW_MODEL, MAC_MODEL)
                    .string(MacFields.COMPILED_CPU_TYPE, data.cpuType),
            )
        }
        val connectivity = ProtoWriter()
            .message(ConnectivityFields.PLATFORM_SPECIFIC_DATA, platform)
            .string(ConnectivityFields.DEVICE_ID, params.deviceId)
        val clientData = ProtoWriter()
            .string(ClientDataFields.CLIENT_VERSION, params.clientVersion)
            .string(ClientDataFields.CLIENT_ID, params.clientId)
            .message(ClientDataFields.CONNECTIVITY_SDK_DATA, connectivity)
        return ProtoWriter()
            .varint(ClientTokenRequestFields.REQUEST_TYPE, REQUEST_CLIENT_DATA)
            .message(ClientTokenRequestFields.CLIENT_DATA, clientData)
            .toByteArray()
    }

    fun clientTokenAnswer(state: String, suffix: Bytes): ByteArray {
        val hashcash = ProtoWriter().string(HashcashAnswerFields.SUFFIX, suffix.hex().uppercase())
        val answer = ProtoWriter()
            .varint(ChallengeAnswerFields.CHALLENGE_TYPE, CHALLENGE_HASHCASH)
            .message(ChallengeAnswerFields.HASH_CASH, hashcash)
        val answers = ProtoWriter()
            .string(ChallengeAnswersFields.STATE, state)
            .message(ChallengeAnswersFields.ANSWERS, answer)
        return ProtoWriter()
            .varint(ClientTokenRequestFields.REQUEST_TYPE, REQUEST_CHALLENGE_ANSWERS)
            .message(ClientTokenRequestFields.CHALLENGE_ANSWERS, answers)
            .toByteArray()
    }

    fun parseClientToken(body: ByteArray): ClientTokenReply {
        val reply = ProtoReader(body)
        while (reply.next()) {
            if (reply.field == ClientTokenResponseFields.GRANTED_TOKEN) {
                var token = ""
                var refreshAfterSeconds = 0L
                val granted = reply.message()
                while (granted.next()) {
                    when (granted.field) {
                        GrantedTokenFields.TOKEN -> token = granted.string()
                        GrantedTokenFields.REFRESH_AFTER_SECONDS -> refreshAfterSeconds = granted.varint
                    }
                }
                if (!granted.failed && token.isNotEmpty()) {
                    return ClientTokenReply.Granted(token, refreshAfterSeconds)
                }
            } else if (reply.field == ClientTokenResponseFields.CHALLENGES) {
                var state = ""
                var hashcash: HashcashChallenge? = null
                val challenges = reply.message()
                while (challenges.next()) {
                    when (challenges.field) {
                        ChallengesFields.STATE -> state = challenges.string()

                        ChallengesFields.CHALLENGES -> if (hashcash == null) {
                            hashcash = parseClientTokenHashcash(challenges.message())
                        }
                    }
                }
                if (!challenges.failed) return ClientTokenReply.Challenge(state, hashcash)
            }
        }
        return ClientTokenReply.Failure("unexpected client token reply of ${body.size} bytes")
    }

    fun login5Request(
        clientId: String,
        deviceId: String,
        credentials: Credentials,
        context: Bytes,
        solution: HashcashSolution?,
    ): ByteArray {
        val clientInfo = ProtoWriter()
            .string(ClientInfoFields.CLIENT_ID, clientId)
            .string(ClientInfoFields.DEVICE_ID, deviceId)
        val stored = ProtoWriter()
            .string(StoredCredentialFields.USERNAME, credentials.username)
            .bytes(StoredCredentialFields.DATA, credentials.data)
        val request = ProtoWriter().message(LoginRequestFields.CLIENT_INFO, clientInfo)
        if (!context.isEmpty()) request.bytes(LoginRequestFields.LOGIN_CONTEXT, context)
        if (solution != null) {
            require(solution.tookNanos >= 0) { "a duration is not negative, got ${solution.tookNanos}" }
            val seconds = solution.tookNanos / NANOS_PER_SECOND
            val nanos = solution.tookNanos % NANOS_PER_SECOND
            val duration = ProtoWriter()
            if (seconds != 0L) duration.varint(DurationFields.SECONDS, seconds)
            if (nanos != 0L) duration.varint(DurationFields.NANOS, nanos)
            val hashcash = ProtoWriter()
                .bytes(HashcashSolutionFields.SUFFIX, solution.suffix)
                .message(HashcashSolutionFields.DURATION, duration)
            val one = ProtoWriter().message(ChallengeSolutionFields.HASHCASH, hashcash)
            val solutions = ProtoWriter().message(ChallengeSolutionsFields.SOLUTIONS, one)
            request.message(LoginRequestFields.CHALLENGE_SOLUTIONS, solutions)
        }
        return request.message(LoginRequestFields.STORED_CREDENTIAL, stored).toByteArray()
    }

    fun parseLogin5(body: ByteArray): Login5Reply {
        var ok: Login5Reply.Ok? = null
        var error: Login5Reply.Error? = null
        var hashcash: HashcashChallenge? = null
        var context = Bytes.EMPTY
        val reply = ProtoReader(body)
        while (reply.next()) {
            when (reply.field) {
                LoginResponseFields.OK -> ok = parseLogin5Ok(reply.message())

                LoginResponseFields.ERROR -> error = Login5Reply.Error(reply.varint.toInt())

                LoginResponseFields.CHALLENGES -> {
                    val challenges = reply.message()
                    while (challenges.next()) {
                        val challenge = challenges.message()
                        while (challenge.next()) {
                            if (challenge.field == Login5ChallengeFields.HASHCASH) {
                                hashcash = parseLogin5Hashcash(challenge.message())
                            }
                        }
                    }
                }

                LoginResponseFields.LOGIN_CONTEXT -> context = Bytes.of(reply.bytes())
            }
        }
        if (reply.failed) return Login5Reply.Failure("malformed login5 reply of ${body.size} bytes")
        if (ok != null && ok.accessToken.isNotEmpty()) return ok
        if (error != null) return error
        if (hashcash != null) return Login5Reply.Challenge(context, hashcash)
        return Login5Reply.Failure("unexpected login5 reply of ${body.size} bytes")
    }

    private fun parseClientTokenHashcash(challenge: ProtoReader): HashcashChallenge? {
        var hashcash: HashcashChallenge? = null
        while (challenge.next()) {
            if (challenge.field != ClientTokenChallengeFields.HASHCASH_PARAMETERS) continue
            var length = 0
            var prefix = ""
            val parameters = challenge.message()
            while (parameters.next()) {
                when (parameters.field) {
                    HashcashParametersFields.LENGTH -> length = parameters.varint.toInt()
                    HashcashParametersFields.PREFIX -> prefix = parameters.string()
                }
            }
            bytesOfHex(prefix)?.let { hashcash = HashcashChallenge(Bytes.of(it), length) }
        }
        return hashcash
    }

    private fun parseLogin5Ok(fields: ProtoReader): Login5Reply.Ok {
        var username = ""
        var accessToken = ""
        var expiresInSeconds = 0L
        while (fields.next()) {
            when (fields.field) {
                LoginOkFields.USERNAME -> username = fields.string()
                LoginOkFields.ACCESS_TOKEN -> accessToken = fields.string()
                LoginOkFields.ACCESS_TOKEN_EXPIRES_IN -> expiresInSeconds = fields.varint
            }
        }
        return Login5Reply.Ok(username, accessToken, expiresInSeconds)
    }

    private fun parseLogin5Hashcash(fields: ProtoReader): HashcashChallenge {
        var prefix = Bytes.EMPTY
        var length = 0
        while (fields.next()) {
            when (fields.field) {
                Login5HashcashFields.PREFIX -> prefix = Bytes.of(fields.bytes())
                Login5HashcashFields.LENGTH -> length = fields.varint.toInt()
            }
        }
        return HashcashChallenge(prefix, length)
    }
}

object Hashcash {
    const val GIVE_UP_AFTER_MS = 5_000L
    const val CHECK_EVERY = 0x10000L

    fun solve(context: Bytes, challenge: HashcashChallenge, giveUp: () -> Boolean): Bytes? {
        val target = readU64(Crypto.sha1(context.toByteArray()), Crypto.SHA1_SIZE - 8)
        val prefix = challenge.prefix.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1")
        val suffix = ByteArray(16)
        var counter = 0L
        while (true) {
            if (counter % CHECK_EVERY == 0L && giveUp()) return null
            writeU64(suffix, 0, target + counter)
            writeU64(suffix, 8, counter)
            digest.update(prefix)
            val hash = digest.digest(suffix)
            val zeros = readU64(hash, Crypto.SHA1_SIZE - 8).countTrailingZeroBits()
            if (zeros >= challenge.length) return Bytes.of(suffix)
            counter++
        }
    }
}

private object ClientTokenRequestFields {
    const val REQUEST_TYPE = 1
    const val CLIENT_DATA = 2
    const val CHALLENGE_ANSWERS = 3
}

private object ClientDataFields {
    const val CLIENT_VERSION = 1
    const val CLIENT_ID = 2
    const val CONNECTIVITY_SDK_DATA = 3
}

private object ConnectivityFields {
    const val PLATFORM_SPECIFIC_DATA = 1
    const val DEVICE_ID = 2
}

private object PlatformFields {
    const val DESKTOP_MACOS = 3
    const val DESKTOP_WINDOWS = 4
    const val DESKTOP_LINUX = 5
}

private object LinuxFields {
    const val SYSTEM_NAME = 1
    const val SYSTEM_RELEASE = 2
    const val SYSTEM_VERSION = 3
    const val HARDWARE = 4
}

private object WindowsFields {
    const val OS_VERSION = 1
    const val OS_BUILD = 3
    const val PLATFORM_ID = 4
    const val UNKNOWN_6 = 6
    const val IMAGE_FILE_MACHINE = 7
    const val PE_MACHINE = 8
    const val UNKNOWN_10 = 10
}

private object MacFields {
    const val SYSTEM_VERSION = 1
    const val HW_MODEL = 2
    const val COMPILED_CPU_TYPE = 3
}

private object ChallengeAnswersFields {
    const val STATE = 1
    const val ANSWERS = 2
}

private object ChallengeAnswerFields {
    const val CHALLENGE_TYPE = 1
    const val HASH_CASH = 4
}

private object HashcashAnswerFields {
    const val SUFFIX = 1
}

private object ClientTokenResponseFields {
    const val GRANTED_TOKEN = 2
    const val CHALLENGES = 3
}

private object GrantedTokenFields {
    const val TOKEN = 1
    const val REFRESH_AFTER_SECONDS = 3
}

private object ChallengesFields {
    const val STATE = 1
    const val CHALLENGES = 2
}

private object ClientTokenChallengeFields {
    const val HASHCASH_PARAMETERS = 4
}

private object HashcashParametersFields {
    const val LENGTH = 1
    const val PREFIX = 2
}

private object LoginRequestFields {
    const val CLIENT_INFO = 1
    const val LOGIN_CONTEXT = 2
    const val CHALLENGE_SOLUTIONS = 3
    const val STORED_CREDENTIAL = 100
}

private object ClientInfoFields {
    const val CLIENT_ID = 1
    const val DEVICE_ID = 2
}

private object StoredCredentialFields {
    const val USERNAME = 1
    const val DATA = 2
}

private object ChallengeSolutionsFields {
    const val SOLUTIONS = 1
}

private object ChallengeSolutionFields {
    const val HASHCASH = 1
}

private object HashcashSolutionFields {
    const val SUFFIX = 1
    const val DURATION = 2
}

private object DurationFields {
    const val SECONDS = 1
    const val NANOS = 2
}

private object LoginResponseFields {
    const val OK = 1
    const val ERROR = 2
    const val CHALLENGES = 3
    const val LOGIN_CONTEXT = 5
}

private object LoginOkFields {
    const val USERNAME = 1
    const val ACCESS_TOKEN = 2
    const val ACCESS_TOKEN_EXPIRES_IN = 4
}

private object Login5ChallengeFields {
    const val HASHCASH = 1
}

private object Login5HashcashFields {
    const val PREFIX = 1
    const val LENGTH = 2
}
