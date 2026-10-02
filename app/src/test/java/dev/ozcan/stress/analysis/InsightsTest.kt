package dev.ozcan.stress.analysis

import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.safety.SafetyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    private fun summary(
        powerValid: Boolean = true,
        throttle: Map<String, Double?> = mapOf("Cortex-A715 ×3" to 42.0, "Cortex-A510 ×4" to 2.0),
        start: Double? = 100.0,
        end: Double? = 75.0,
        stability: Double? = 0.83,
        maxTemps: Map<String, Double> = mapOf("cpu" to 86.0, "gpu" to 98.0, "battery" to 41.0),
        startTemps: Map<String, Double> = mapOf("cpu" to 40.0, "battery" to 33.0),
        errors: Long = 0,
        levels: Pair<Int, Int> = 80 to 74,
        fps: Double? = null,
    ) = RunSummary(
        idleWatts = 1.0, peakWatts = 9.0, meanWatts = 8.0, sustainedWatts = 7.5, energyWattHours = 0.5,
        batteryStartPercent = levels.first, batteryEndPercent = levels.second, batteryLifeHours = 2.0,
        maxTemperatures = maxTemps, startTemperatures = startTemps, firstThrottleSeconds = throttle,
        cpuStability = stability, gpuStability = null, computationErrors = errors, powerValid = powerValid,
        cpuStartRate = start, cpuEndRate = end, meanFps = fps, minFps = fps?.minus(3),
    )

    private fun record(summary: RunSummary, end: EndReason = EndReason.Completed, seconds: Double = 600.0, reason: SafetyReason? = null) =
        RunRecord(
            id = "x", startedAtMillis = 0, mode = "Full", workload = "fp32_l2", plannedSeconds = 600, loadSeconds = seconds,
            stoppedEarly = end != EndReason.Completed, summary = summary,
            series = RunSeries(emptyList(), emptyList(), emptyMap(), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList()),
            endReason = end.name, safetyReason = reason?.name, safetyValue = reason?.let { 47.2 },
        )

    @Test
    fun `a typical full load run reads as expected`() {
        val insights = Insights.of(record(summary()))
        val kinds = insights.map { it::class.simpleName }
        // Cautions first: the GPU at its throttling point and the early throttling.
        assertEquals(Severity.Warn, insights.first().severity)
        assertTrue(insights.any { it is Insight.ChipPeak && it.key == "gpu" && it.celsius == 98.0 && it.severity == Severity.Warn })
        val throttled = insights.filterIsInstance<Insight.Throttled>().single()
        assertEquals("Cortex-A510 ×4", throttled.cluster)
        assertEquals(Severity.Warn, throttled.severity)
        val drop = insights.filterIsInstance<Insight.PerformanceDrop>().single()
        assertEquals(0.25, drop.drop, 1e-9)
        assertEquals(Severity.Warn, drop.severity)
        assertEquals(StabilityGrade.Fair, insights.filterIsInstance<Insight.Stability>().single().grade)
        // 6 % in ten minutes: 36 % an hour.
        assertEquals(36.0, insights.filterIsInstance<Insight.BatteryDrain>().single().percentPerHour, 1e-9)
        assertTrue("ComputationErrors" in kinds)
        assertTrue(insights.none { it is Insight.FrameRate })
    }

    @Test
    fun `problems lead the list`() {
        val insights = Insights.of(record(summary(errors = 3), end = EndReason.GpuFailed))
        assertEquals(listOf(Severity.Bad, Severity.Bad), insights.take(2).map { it.severity })
        assertTrue(insights.take(2).any { it == Insight.GpuFailed })
        assertTrue(insights.take(2).any { it is Insight.ComputationErrors && it.count == 3L })
    }

    @Test
    fun `a safety stop carries its reason and reading`() {
        val stop = Insights.of(record(summary(), end = EndReason.Safety, reason = SafetyReason.BatteryHot))
            .filterIsInstance<Insight.EndedBySafety>().single()
        assertEquals(SafetyReason.BatteryHot, stop.reason)
        assertEquals(47.2, stop.value!!, 0.0)
    }

    @Test
    fun `a charger hides the power findings and a steady run reads well`() {
        val insights = Insights.of(
            record(summary(powerValid = false, throttle = mapOf("c" to null), start = 100.0, end = 99.0, stability = 0.97, fps = 24.0)),
        )
        assertTrue(insights.contains(Insight.Charging))
        assertTrue(insights.contains(Insight.NeverThrottled))
        assertEquals(Severity.Good, insights.filterIsInstance<Insight.PerformanceDrop>().single().severity)
        assertEquals(StabilityGrade.Excellent, insights.filterIsInstance<Insight.Stability>().single().grade)
        assertTrue(insights.none { it is Insight.BatteryDrain })
        assertEquals(24.0, insights.filterIsInstance<Insight.FrameRate>().single().mean, 0.0)
    }

    @Test
    fun `stability grades`() {
        assertEquals(StabilityGrade.Excellent, StabilityGrade.of(0.95))
        assertEquals(StabilityGrade.Good, StabilityGrade.of(0.9))
        assertEquals(StabilityGrade.Fair, StabilityGrade.of(0.7))
        assertEquals(StabilityGrade.Poor, StabilityGrade.of(0.69))
    }

    @Test
    fun `runs stored by the first version, with Honor-specific keys, still read`() {
        val old = summary(maxTemps = mapOf("A715" to 85.6, "A510" to 85.6, "GPU" to 81.6, "DDR" to 72.8, "Pil" to 33.0), startTemps = mapOf("Pil" to 31.0))
        val insights = Insights.of(record(old).copy(endReason = null))
        assertTrue(insights.any { it is Insight.ChipPeak && it.celsius == 85.6 })
        assertTrue(insights.any { it is Insight.BatteryWarmed && it.from == 31.0 && it.to == 33.0 })
    }
}
