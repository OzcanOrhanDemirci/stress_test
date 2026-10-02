package dev.ozcan.stress.analysis

import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import org.junit.Assert.assertEquals
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
    fun `power measured on a charger is left out`() {
        val row = Comparison.rows(record(peak = 10.0), record(peak = 9.0, powerValid = false)).first { it.metric == Comparison.Metric.PeakPower }
        assertNull(row.b)
        assertNull(row.change)
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
