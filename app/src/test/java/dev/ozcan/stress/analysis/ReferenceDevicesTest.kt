package dev.ozcan.stress.analysis

import dev.ozcan.stress.run.StressMode
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The measurements that ship with the app: real modes, plausible watts. */
class ReferenceDevicesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun bundled(): List<ReferenceDevice> =
        json.decodeFromString(File("src/main/assets/reference_devices.json").readText())

    @Test
    fun `the bundled reference names real modes`() {
        val devices = bundled()
        assertTrue(devices.isNotEmpty())
        for (device in devices) {
            for (key in device.watts.keys) {
                assertNotNull("mode '$key' does not exist", StressMode.of(key))
            }
        }
    }

    @Test
    fun `every bundled number is a power a phone could draw`() {
        for (device in bundled()) {
            for (watts in device.watts.values) {
                assertTrue("$device drew $watts W", watts in 1.0..15.0)
            }
        }
    }

    @Test
    fun `the reference phone measured the five power modes`() {
        val honor = bundled().first()
        assertEquals("Honor 400", honor.name)
        for (mode in listOf("Full", "Cinematic", "Cpu", "Gpu", "Dry")) {
            assertTrue("no watts for $mode", honor.watts.containsKey(mode))
        }
        // The whole point of the app: full load draws the most.
        assertTrue(honor.watts.getValue("Full") > honor.watts.getValue("Cpu"))
        assertTrue(honor.watts.getValue("Full") > honor.watts.getValue("Gpu"))
    }
}
