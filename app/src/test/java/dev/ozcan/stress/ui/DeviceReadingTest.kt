package dev.ozcan.stress.ui

import dev.ozcan.stress.ui.device.deviceClock
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReadingTest {
    @Test
    fun `slow clocks retain kHz and missing clocks stay missing`() {
        assertEquals("800 kHz", deviceClock(800))
        assertEquals("1800 MHz", deviceClock(1_800_000))
        assertEquals(Format.MISSING, deviceClock(0))
        assertEquals(Format.MISSING, deviceClock(null))
    }
}
