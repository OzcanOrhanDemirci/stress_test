package dev.ozcan.stress.analysis

import dev.ozcan.stress.TestSamples.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerTest {

    @Test
    fun `infers microamps with negative discharge`() {
        val c = CurrentConvention.infer(listOf(-900_000L, -1_100_000L, -1_000_000L, 0L))!!
        assertEquals(1e-6, c.ampsPerUnit, 0.0)
        assertEquals(-1, c.dischargeSign)
        assertEquals(1.0, c.dischargeAmps(-1_000_000), 1e-12)
    }

    @Test
    fun `infers milliamps with positive discharge`() {
        val c = CurrentConvention.infer(listOf(850L, 900L, 1200L))!!
        assertEquals(1e-3, c.ampsPerUnit, 0.0)
        assertEquals(1, c.dischargeSign)
        assertEquals(0.9, c.dischargeAmps(900), 1e-12)
    }

    @Test
    fun `needs three non-zero readings`() {
        assertNull(CurrentConvention.infer(listOf(0L, 0L, -5L, -6L)))
    }

    @Test
    fun `watts are discharge current times battery voltage and skip plugged samples`() {
        val convention = CurrentConvention(1e-6, -1)
        val samples = listOf(
            sample(0.0, currentRaw = -1_000_000, millivolts = 4000),
            sample(0.1, currentRaw = -2_000_000, millivolts = 3800),
            sample(0.2, currentRaw = 500_000, millivolts = 4200, plugged = true),
            sample(0.3, currentRaw = null),
        )
        val watts = Power.watts(samples, convention, originNanos = 0)
        assertEquals(listOf(0.0, 0.1), watts.map { it.seconds })
        assertEquals(4.0, watts[0].value, 1e-9)
        assertEquals(7.6, watts[1].value, 1e-9)
    }

    @Test
    fun `charge counter slope gives the mean discharge current`() {
        // 1 A for 60 s drains 1_000_000 µA * 60 s / 3600 s/h = 16_666.7 µAh.
        val samples = (0..600).map { i ->
            val t = i / 10.0
            sample(t, chargeCounter = (4_000_000 - 1_000_000 * t / 3600).toLong())
        }
        assertEquals(1.0, Power.chargeCounterAmps(samples, 0)!!, 0.01)
    }

    @Test
    fun `a flat charge counter means no current`() {
        val samples = (0..10).map { sample(it.toDouble(), chargeCounter = 3_000_000) }
        assertEquals(0.0, Power.chargeCounterAmps(samples, 0)!!, 1e-12)
    }
}
