package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.ApCpuFamily
import io.github.kickoman.qiyaa.spotify.protocol.ApOs
import io.github.kickoman.qiyaa.spotify.protocol.ApPlatform
import io.github.kickoman.qiyaa.spotify.protocol.ClientIdentity
import io.github.kickoman.qiyaa.spotify.protocol.ClientPlatform
import io.github.kickoman.qiyaa.spotify.protocol.ClientTokenParams
import io.github.kickoman.qiyaa.spotify.protocol.toHex
import java.security.SecureRandom

class SpotifyIdentity(
    val appVersion: String,
    val deviceId: String,
    osRelease: String,
    androidVersion: String,
    abi: String,
) {
    private val cpu = Cpu.of(abi)
    private val name = "QiYaa $appVersion"

    val accessPoint: ClientIdentity =
        ClientIdentity(
            platform = cpu.platform,
            cpuFamily = cpu.family,
            os = ApOs.LINUX,
            systemInformation = name,
            versionString = name,
            deviceId = deviceId,
        )

    val clientToken: ClientTokenParams =
        ClientTokenParams(
            clientVersion = CLIENT_VERSION,
            clientId = CLIENT_ID,
            deviceId = deviceId,
            platform = ClientPlatform.Linux(osRelease, androidVersion, cpu.hardware ?: abi),
        )

    val userAgent: String = "Spotify/${ClientIdentity.PROTOCOL_VERSION} Linux/0 ($name)"

    override fun toString(): String =
        "SpotifyIdentity(appVersion=$appVersion, deviceId=<${deviceId.length} chars>, cpu=$cpu)"

    private enum class Cpu(val platform: ApPlatform, val family: ApCpuFamily, val hardware: String?) {
        ARM_64(ApPlatform.LINUX_ARM, ApCpuFamily.ARM, "aarch64"),
        ARM_32(ApPlatform.LINUX_ARM, ApCpuFamily.ARM, "arm"),
        X86_64(ApPlatform.LINUX_X86_64, ApCpuFamily.X86_64, "x86_64"),
        X86(ApPlatform.LINUX_X86, ApCpuFamily.X86, "i386"),
        OTHER(ApPlatform.LINUX_X86, ApCpuFamily.UNKNOWN, null),
        ;

        companion object {
            fun of(abi: String): Cpu = when {
                abi.startsWith("arm64") -> ARM_64
                abi.startsWith("arm") -> ARM_32
                abi == "x86_64" -> X86_64
                abi == "x86" -> X86
                else -> OTHER
            }
        }
    }

    companion object {
        const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
        const val CLIENT_VERSION = "1.2.52.442"
        const val DEVICE_ID_BYTES = 20

        fun newDeviceId(random: SecureRandom = SecureRandom()): String =
            ByteArray(DEVICE_ID_BYTES).also(random::nextBytes).toHex()
    }
}
