package dev.ozcan.stress.analysis

import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonTest {

    private fun record(
        peak: Double?,
        powerValid: Boolean = true,
        temps: Map<String, Double> = mapOf("cpu" to 86.0, "gpu" to 95.0, "battery" to 41.0),
        throttle: Map<String, Double?> = mapOf("c" to 30.0),
        seconds: Int = 3,
        cpu: List<Double?> = listOf(80.0, 82.0, 85.0),
        gpu: List<Double?> = listOf(90.0, 96.0, 97.0),
    ) = RunRecord(
        id = "r", startedAtMillis = 0, mode = "Full", workload = "fp32_l2", plannedSeconds = null, loadSeconds = seconds.toDouble(),
        stoppedEarly = false,
        summary = RunSummary(
            idleWatts = 1.0, peakWatts = peak, meanWatts = peak?.minus(1), sustainedWatts = peak?.minus(2), energyWattHours = 0.1,
            batteryStartPercent = 80, batteryEndPercent = 79, batteryLifeHours = 3.0, maxTemperatures = temps,
            startTemperatures = emptyMap(), firstThrottleSeconds = throttle, cpuStability = 0.9, gpuStability = null,
            computationErrors = 0, powerValid = powerValid, cpuMeanRate = 1.2e11,
        ),
        series = RunSeries(
            seconds = List(seconds) { it.toDouble() }, watts = List(seconds) { null },
            temperatures = mapOf("cpu" to cpu.take(seconds), "gpu" to gpu.take(seconds)),
            batteryCelsius = List(seconds) { 40.0 }, clocksMhz = emptyMap(), cpuRelative = List(seconds) { 1.0 },
            gpuRelative = List(seconds) { null }, batteryPercent = List(seconds) { 80 },
        ),
    )

    @Test
    fun `rows show what either run measured, with the change from A to B`() {
        val rows = Comparison.rows(record(peak = 10.0), record(peak = 11.5, temps = mapOf("cpu" to 80.0, "battery" to 44.0)))
        val peak = rows.first { it.metric == Comparison.Metric.PeakPower }
        assertEquals(1.5, peak.difference!!, 1e-9)
        assertEquals(0.15, peak.change!!, 1e-9)
        val chip = rows.first { it.metric == Comparison.Metric.HottestChip }
        assertEquals(95.0, chip.a!!, 0.0)
        assertEquals(80.0, chip.b!!, 0.0)
        assertEquals(44.0, rows.first { it.metric == Comparison.Metric.HottestBattery }.b!!, 0.0)
        // Neither run drew a scene: no frame-rate row.
        assertTrue(rows.none { it.metric == Comparison.Metric.Fps })
        assertEquals(30.0, rows.first { it.metric == Comparison.Metric.FirstThrottle }.a!!, 0.0)
    }

    @Test
    fun `temperatures, times and counts differ in their own unit, as the screen shows them`() {
        val a = record(peak = 10.0, temps = mapOf("cpu" to 87.64, "battery" to 35.0), throttle = mapOf("c" to 6.9))
        val b = record(peak = 11.0, temps = mapOf("cpu" to 88.36, "battery" to 40.0), throttle = mapOf("c" to 7.1))
        val rows = Comparison.rows(a, b).associateBy { it.metric }
        // Shown as 87.6 and 88.4 °C: the difference is 0.8 °C, not the raw 0.72.
        assertEquals(0.8, rows.getValue(Comparison.Metric.HottestChip).shownDifference!!, 1e-9)
        assertEquals(5.0, rows.getValue(Comparison.Metric.HottestBattery).shownDifference!!, 1e-9)
        // The clock shows 0:06 and 0:07.
        assertEquals(1.0, rows.getValue(Comparison.Metric.FirstThrottle).shownDifference!!, 0.0)
        assertEquals(0.0, rows.getValue(Comparison.Metric.Errors).shownDifference!!, 0.0)
        // Power keeps its percent change and has no unit difference.
        assertNull(rows.getValue(Comparison.Metric.PeakPower).shownDifference)
        assertEquals(Comparison.Delta.Percent, Comparison.Metric.CpuRate.delta)
        assertEquals(Comparison.Delta.Degrees, Comparison.Metric.HottestChip.delta)
    }

    @Test
    fun `rounding follows the screen's half-up print`() {
        assertEquals(87.6, Comparison.rounded(87.64, 1), 0.0)
        assertEquals(88.4, Comparison.rounded(88.35, 1), 0.0)
        assertEquals(2.35, Comparison.rounded(2.345, 2), 0.0)
    }

    @Test
    fun `power measured on a charger is left out`() {
        val row = Comparison.rows(record(peak = 10.0), record(peak = 9.0, powerValid = false)).first { it.metric == Comparison.Metric.PeakPower }
        assertNull(row.b)
        assertNull(row.difference)
        assertNull(row.change)
    }

    @Test
    fun `comparisons exclude reductions in tier or scale and unsettled probes`() {
        val medium = record(peak = 10.0).copy(mode = "Cinematic", quality = "Medium", drawnQuality = "Medium")
        assertTrue(Comparison.isEligible(medium))
        assertTrue(Comparison.isEligible(medium.copy(drawnScalePercent = 45.0)))
        assertFalse(Comparison.isEligible(medium.copy(quality = "High", drawnScalePercent = 45.0)))
        assertFalse(Comparison.isEligible(medium.copy(drawnScalePercent = 44.75)))
        assertFalse(Comparison.isEligible(medium.copy(drawnQuality = null)))
    }

    @Test
    fun `reference scene watts apply only to a full scale medium pool run`() {
        val honor = ReferenceDevice("Honor 400", "Adreno 720", "measurements", mapOf("Cinematic" to 8.9))
        val medium = record(peak = 10.0).copy(mode = "Cinematic", quality = "Medium", drawnQuality = "Medium", drawnScalePercent = 45.0)
        assertEquals(8.9, honor.wattsFor(medium))
        assertNull(honor.wattsFor(medium.copy(quality = "High")))
        assertNull(honor.wattsFor(medium.copy(quality = "High", drawnQuality = "High", drawnScalePercent = 65.0)))
        assertNull(honor.wattsFor(medium.copy(summary = medium.summary.copy(gpuUnit = "GFLOPS"))))
    }

    @Test
    fun `the chip curve is the hotter of CPU and GPU each second`() {
        assertEquals(listOf(90.0, 96.0, 97.0), Comparison.chipSeries(record(peak = 1.0)))
    }

    @Test
    fun `curves share the longer run's time axis`() {
        val (seconds, a, b) = Comparison.align(listOf(0.0, 1.0), listOf(1.0, 2.0), listOf(0.0, 1.0, 2.0), listOf(5.0, null, 7.0))
        assertEquals(listOf(0.0, 1.0, 2.0), seconds)
        assertEquals(listOf(1.0, 2.0, null), a)
        assertEquals(listOf(5.0, null, 7.0), b)
    }
}
