package io.github.kickoman.qiyaa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineIdTest {
    @Test
    fun `the hash of abc is the desktop's test vector`() {
        assertEquals("3a8271ff2b263f300989f47f507a3804", MachineId.of("abc"))
    }

    @Test
    fun `the spec examples' machine is the hash of their ANDROID_ID`() {
        assertEquals("97eaa8cc2fbe9a456389e6bd9d44a3fb", MachineId.of("3f2b9c1d7a6e5048"))
    }

    @Test
    fun `ANDROID_ID wins and nothing is stored`() {
        val stored = ArrayList<String>()
        val machine = MachineId.resolve("3f2b9c1d7a6e5048", UUID, stored::add)
        assertEquals("97eaa8cc2fbe9a456389e6bd9d44a3fb", machine)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `without ANDROID_ID the stored id is hashed and kept as it is`() {
        for (androidId in listOf(null, "", " ")) {
            val stored = ArrayList<String>()
            assertEquals("$androidId", MachineId.of(UUID), MachineId.resolve(androidId, UUID, stored::add))
            assertEquals("$androidId", emptyList<String>(), stored)
        }
    }

    @Test
    fun `with neither a new id is stored once and the result stays the same`() {
        var setting: String? = null
        var writes = 0
        val keep: (String) -> Unit = { id ->
            setting = id
            writes++
        }
        val first = MachineId.resolve(null, setting, keep)
        val second = MachineId.resolve(null, setting, keep)
        assertEquals(1, writes)
        assertEquals(first, second)
        assertTrue(first.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `the result never contains the raw id`() {
        val raw = "3f2b9c1d7a6e5048"
        val machine = MachineId.resolve(raw, null, {})
        assertFalse(machine.contains(raw))
        assertNotEquals(raw, machine)
    }

    private companion object {
        const val UUID = "0d6e4c8a-6a3b-4f1e-9a57-3c2b1d0e9f84"
    }
}
