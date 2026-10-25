package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.ApCpuFamily
import io.github.kickoman.qiyaa.spotify.protocol.ApOs
import io.github.kickoman.qiyaa.spotify.protocol.ApPlatform
import io.github.kickoman.qiyaa.spotify.protocol.ClientIdentity
import io.github.kickoman.qiyaa.spotify.protocol.ClientPlatform
import io.github.kickoman.qiyaa.spotify.protocol.ClientTokenParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyIdentityTest {
    @Test
    fun `an arm64 phone presents itself as the desktop on Linux ARM`() {
        val identity = SpotifyIdentity("0.9.0", DEVICE_ID, "6.1.25-android16-11", "16", "arm64-v8a")
        assertEquals(
            ClientIdentity(
                platform = ApPlatform.LINUX_ARM,
                cpuFamily = ApCpuFamily.ARM,
                os = ApOs.LINUX,
                systemInformation = "QiYaa 0.9.0",
                versionString = "QiYaa 0.9.0",
                deviceId = DEVICE_ID,
                protocolVersion = 124_200_290,
            ),
            identity.accessPoint,
        )
        assertEquals(
            ClientTokenParams(
                clientVersion = "1.2.52.442",
                clientId = "65b708073fc0480ea92a077233ca87bd",
                deviceId = DEVICE_ID,
                platform = ClientPlatform.Linux("6.1.25-android16-11", "16", "aarch64"),
            ),
            identity.clientToken,
        )
        assertEquals("Spotify/124200290 Linux/0 (QiYaa 0.9.0)", identity.userAgent)
    }

    @Test
    fun `32-bit ARM is arm, an x86-64 phone or emulator is Linux x86-64`() {
        val arm = SpotifyIdentity("0.9.0", DEVICE_ID, "4.19", "13", "armeabi-v7a")
        assertEquals(ApPlatform.LINUX_ARM, arm.accessPoint.platform)
        assertEquals(ApCpuFamily.ARM, arm.accessPoint.cpuFamily)
        assertEquals(ClientPlatform.Linux("4.19", "13", "arm"), arm.clientToken.platform)
        val emulator = SpotifyIdentity("0.9.0", DEVICE_ID, "6.6", "15", "x86_64")
        assertEquals(ApPlatform.LINUX_X86_64, emulator.accessPoint.platform)
        assertEquals(ApCpuFamily.X86_64, emulator.accessPoint.cpuFamily)
        assertEquals(ApOs.LINUX, emulator.accessPoint.os)
        assertEquals(ClientPlatform.Linux("6.6", "15", "x86_64"), emulator.clientToken.platform)
    }

    @Test
    fun `other CPUs follow the desktop's rule for Linux`() {
        val x86 = SpotifyIdentity("0.9.0", DEVICE_ID, "4.4", "8.0.0", "x86")
        assertEquals(ApPlatform.LINUX_X86, x86.accessPoint.platform)
        assertEquals(ApCpuFamily.X86, x86.accessPoint.cpuFamily)
        assertEquals(ClientPlatform.Linux("4.4", "8.0.0", "i386"), x86.clientToken.platform)
        val riscv = SpotifyIdentity("0.9.0", DEVICE_ID, "6.6", "15", "riscv64")
        assertEquals(ApPlatform.LINUX_X86, riscv.accessPoint.platform)
        assertEquals(ApCpuFamily.UNKNOWN, riscv.accessPoint.cpuFamily)
        assertEquals(ClientPlatform.Linux("6.6", "15", "riscv64"), riscv.clientToken.platform)
    }

    @Test
    fun `a new device id is 20 random bytes as 40 hex digits`() {
        val first = SpotifyIdentity.newDeviceId()
        assertTrue(first, first.matches(Regex("[0-9a-f]{40}")))
        assertNotEquals(first, SpotifyIdentity.newDeviceId())
    }

    @Test
    fun `the device id is not printed`() {
        val identity = SpotifyIdentity("0.9.0", DEVICE_ID, "6.6", "15", "arm64-v8a")
        assertFalse(identity.toString(), identity.toString().contains(DEVICE_ID))
    }

    private companion object {
        val DEVICE_ID = "0123456789abcdef".repeat(2) + "01234567"
    }
}
