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
    fun `voltage in volts or microvolts becomes millivolts`() {
        assertEquals(4120, Power.normalizeMillivolts(4120))
        assertEquals(4000, Power.normalizeMillivolts(4))
        assertEquals(4120, Power.normalizeMillivolts(4_120_000))
        assertNull(Power.normalizeMillivolts(0))
        assertNull(Power.normalizeMillivolts(-1))
        assertNull(Power.normalizeMillivolts(500))
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

    @Test
    fun `a charge counter in mAh is turned into µAh`() {
        assertEquals(5_679_000L, Power.normalizeChargeCounter(5679))
        assertEquals(4_000_000L, Power.normalizeChargeCounter(4_000_000))
        assertEquals(80_000L, Power.normalizeChargeCounter(80_000, 2))
        assertNull(Power.normalizeChargeCounter(0))
        assertNull(Power.normalizeChargeCounter(Long.MIN_VALUE))
        assertNull(Power.normalizeChargeCounter(5000, 1))
    }

    @Test
    fun `gauge conventions are detected from the stream without a brand default`() {
        // Representative Android HAL fixtures, including signed firmware
        // variants. They are conventions a model can expose, not brand rules.
        val fixtures = listOf(
            Triple("Honor 400", listOf(-531L, -543L, -528L), CurrentConvention(1e-3, -1)),
            Triple("Pixel", listOf(-530_000L, -540_000L, -525_000L), CurrentConvention(1e-6, -1)),
            Triple("Samsung", listOf(460_000L, 470_000L, 475_000L), CurrentConvention(1e-6, 1)),
            Triple("Xiaomi / Redmi / POCO", listOf(-780_000L, -806_000L, -790_000L), CurrentConvention(1e-6, -1)),
            Triple("OnePlus / Oppo / Realme", listOf(-510L, -519L, -525L), CurrentConvention(1e-3, -1)),
            Triple("Vivo / MediaTek", listOf(415L, 407L, 422L), CurrentConvention(1e-3, 1)),
            Triple("Huawei", listOf(-320L, -328L, -335L), CurrentConvention(1e-3, -1)),
            Triple("Motorola", listOf(-334_000L, -340_000L, -352_000L), CurrentConvention(1e-6, -1)),
        )
        for ((vendor, readings, expected) in fixtures) assertEquals(vendor, expected, CurrentConvention.infer(readings))
        assertNull(CurrentConvention.infer(List(10) { 0L }))
        assertNull(CurrentConvention.infer(List(10) { Long.MIN_VALUE }))
        assertNull(CurrentConvention.infer(listOf(20_000L, 21_000L, 22_000L)))
        assertNull(CurrentConvention.infer(listOf(540L, -540L, 541L, -541L)))
    }

    @Test
    fun `a reported pack voltage is used as given and invalid values are dropped`() {
        assertEquals(8_400, Power.normalizeMillivolts(8_400_000))
        assertEquals(8_400, Power.normalizeMillivolts(8_400))
        assertNull(Power.normalizeMillivolts(15))
        assertNull(Power.normalizeMillivolts(Int.MAX_VALUE))
        assertNull(Power.normalizeMillivolts(2_000_000))
    }

    @Test
    fun `mean power is weighted by time when the sampler changes cadence`() {
        val points = listOf(Point(0.0, 4.0), Point(1.0, 4.0), Point(2.0, 8.0), Point(2.1, 8.0), Point(3.0, 8.0))
        assertEquals(6.0, Power.meanWatts(points)!!, 1e-9)
    }
}
