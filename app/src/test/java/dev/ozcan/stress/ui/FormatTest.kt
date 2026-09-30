package dev.ozcan.stress.ui

import dev.ozcan.stress.engine.WorkUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `numbers use a decimal comma and a dash when missing`() {
        assertEquals("7,25 W", Format.watts(7.2489))
        assertEquals(Format.MISSING, Format.watts(null))
        assertEquals("41,5 °C", Format.celsius(41.49))
        assertEquals("2400 MHz", Format.mhz(2_400_000))
        assertEquals("%31", Format.percent(0.3073))
    }

    @Test
    fun `rates pick an SI prefix`() {
        assertEquals("123,4 GFLOPS", Format.rate(1.234e11, WorkUnit.Flop))
        assertEquals("1,5 TOPS", Format.rate(1.5e12, WorkUnit.Op))
        assertEquals("850,0 MB/s", Format.rate(8.5e8, WorkUnit.Byte))
        assertEquals(Format.MISSING, Format.rate(null, WorkUnit.Op))
    }
}
