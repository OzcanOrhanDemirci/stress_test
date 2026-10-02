package dev.ozcan.stress.ui

import dev.ozcan.stress.ui.components.niceStep
import dev.ozcan.stress.ui.components.niceTicks
import dev.ozcan.stress.ui.components.tickLabel
import dev.ozcan.stress.ui.components.timeStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ChartAndFormatTest {

    private val saved = Locale.getDefault()

    @Before
    fun turkish() = Locale.setDefault(Locale.forLanguageTag("tr-TR"))

    @After
    fun restore() = Locale.setDefault(saved)

    @Test
    fun `ticks are round numbers inside the range`() {
        assertEquals(listOf(0.0, 2.5, 5.0, 7.5, 10.0), niceTicks(0.0, 10.0))
        assertEquals(listOf(40.0, 50.0, 60.0, 70.0), niceTicks(35.2, 71.9))
        assertEquals(listOf(1000.0, 1500.0, 2000.0, 2500.0), niceTicks(900.0, 2630.0))
    }

    @Test
    fun `a flat or broken range gives a single tick`() {
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0))
        assertEquals(1, niceTicks(Double.NaN, 1.0).size)
    }

    @Test
    fun `tick labels carry the decimals their step needs`() {
        // A flat battery level at 100 once labelled 99,5 / 100 / 100,5 as "100" / "100" / "101".
        val step = niceStep(99.0, 101.0)
        assertEquals(listOf("99,0", "99,5", "100,0", "100,5", "101,0"), niceTicks(99.0, 101.0).map { tickLabel(it, step) })
        assertEquals("2000", tickLabel(2000.0, niceStep(0.0, 2630.0)))
        assertEquals("7,5", tickLabel(7.5, niceStep(0.0, 10.0)))
    }

    @Test
    fun `time labels step by the length of the run and the room for them`() {
        assertEquals(10.0, timeStep(60.0), 0.0)
        assertEquals(30.0, timeStep(150.0), 0.0)
        assertEquals(60.0, timeStep(300.0), 0.0)
        assertEquals(300.0, timeStep(1800.0), 0.0)
        // A 34-minute run in a narrow chart: five labels at most.
        assertEquals(600.0, timeStep(2078.0, maxLabels = 5), 0.0)
        assertEquals(3600.0, timeStep(10 * 3600.0, maxLabels = 4), 0.0)
    }

    @Test
    fun `durations read like a clock`() {
        assertEquals("0:00", Format.clock(0.4))
        assertEquals("2:05", Format.clock(125.9))
        assertEquals("1:02:05", Format.clock(3725.0))
    }

    @Test
    fun `turkish numbers use a decimal comma and put the percent sign first`() {
        assertEquals("7,25 W", Format.watts(7.2489))
        assertEquals(Format.MISSING, Format.watts(null))
        assertEquals("41,5 °C", Format.celsius(41.49))
        assertEquals("2400 MHz", Format.mhz(2_400_000))
        assertEquals("%31", Format.percent(0.3073))
        assertEquals("-%6", Format.percent(-0.06))
        assertEquals("%0", Format.percent(-0.001))
        assertEquals("%5", Format.percentValue(5))
        assertEquals(Format.MISSING, Format.number(Double.NaN, 1))
    }

    @Test
    fun `english numbers use a decimal point and put the percent sign last`() {
        Locale.setDefault(Locale.US)
        assertEquals("7.25 W", Format.watts(7.2489))
        assertEquals("31%", Format.percent(0.3073))
        assertEquals("-6%", Format.percent(-0.06))
        assertEquals("5%", Format.percentValue(5))
    }

    @Test
    fun `rates pick an SI prefix`() {
        assertEquals("123,4 GFLOPS", Format.rate(1.234e11, "FLOPS"))
        assertEquals("1,5 TOPS", Format.rate(1.5e12, "OPS"))
        assertEquals("850,0 MB/s", Format.rate(8.5e8, "B/s"))
        assertEquals(Format.MISSING, Format.rate(null, "OPS"))
        assertEquals("123,4" to "GFLOPS", Format.rateParts(1.234e11, "FLOPS"))
    }

    @Test
    fun `upper case follows the language`() {
        assertEquals("GRAFİK KALİTESİ", "Grafik kalitesi".upper())
        Locale.setDefault(Locale.US)
        assertEquals("GRAPHICS QUALITY", "Graphics quality".upper())
    }
}
