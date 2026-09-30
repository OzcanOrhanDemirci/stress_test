package dev.ozcan.stress.run

import dev.ozcan.stress.TestSamples
import dev.ozcan.stress.TestSamples.sample
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAnalysisTest {

    /**
     * 10 s at rest drawing 0.5 A, then 180 s of load at 4.0 V: 9 W for the
     * first minute, 7 W after. The A715 cluster falls to 80% of its clock at
     * 40 s. CPU work runs at 100 batches/s a CPU for a minute, then 80; the
     * GPU at 50 units/s, then 40. Sampled at 10 Hz; the charge counter
     * starts at 4 Ah, at 80%.
     */
    private fun run(plugged: Boolean = false): Pair<List<Sample>, List<Sample>> {
        var t = 0.0
        val baseline = (0 until 100).map {
            sample(t, currentRaw = -500_000, cpu = TestSamples.snapshot(null, 0)).also { t += 0.1 }
        }
        var cpuWork = 0.0
        var gpuWork = 0L
        val load = (0 until 1800).map { i ->
            val s = i / 10.0
            val amps = if (s < 60) 2.25 else 1.75
            val bigKhz = if (s < 40) 2_400_000L else 1_920_000L
            // The engine reports whole batches of 10 iterations of 1000 units: 10_000 units a batch.
            val cpu = TestSamples.snapshot(TestSamples.gemm, batches = (cpuWork / 10_000 / 8).toLong())
            val gpu = TestSamples.gpu(TestSamples.gpuFp32, frames = i.toLong(), workPerFrame = 0).copy(work = gpuWork)
            val temps = mapOf(ThermalGroup.BigCores to 40.0 + s / 10, ThermalGroup.Gpu to 35.0 + s / 20)
            val sampleAt = sample(
                t, currentRaw = (-amps * 1_000_000).toLong(), chargeCounter = 4_000_000, cpu = cpu, gpu = gpu,
                freqKhz = listOf(1_804_800, bigKhz, 2_630_400), temperatures = temps, plugged = plugged && i == 900,
            )
            cpuWork += (if (s < 60) 100.0 else 80.0) * 0.1 * 8 * 10_000
            gpuWork += if (s < 60) 5L else 4L
            t += 0.1
            sampleAt
        }
        return baseline to load
    }

    private fun analyze(plugged: Boolean = false) =
        run(plugged).let { (b, l) -> RunAnalysis.analyze(b, l, TestSamples.clusters) { "p${it.policy}" } }

    @Test
    fun `power figures come out of current and voltage`() {
        val (summary, _) = analyze()
        assertEquals(2.0, summary.idleWatts!!, 1e-9)
        assertEquals(9.0, summary.peakWatts!!, 1e-9)
        assertEquals((60 * 9.0 + 120 * 7.0) / 180, summary.meanWatts!!, 0.01)
        // Shorter than five minutes: sustained is the whole load.
        assertEquals(summary.meanWatts, summary.sustainedWatts!!, 1e-9)
        assertEquals(summary.meanWatts * 179.9 / 3600, summary.energyWattHours!!, 0.002)
        assertTrue(summary.powerValid)
    }

    @Test
    fun `battery life follows the gauge's full charge and the sustained power`() {
        val (summary, _) = analyze()
        // 4 Ah at 80% means 5 Ah full, 19.35 Wh at 3.87 V.
        assertEquals(19.35 / summary.sustainedWatts!!, summary.batteryLifeHours!!, 1e-6)
        assertEquals(80, summary.batteryStartPercent)
    }

    @Test
    fun `throttling, stability and temperatures`() {
        val (summary, _) = analyze()
        assertNull(summary.firstThrottleSeconds.getValue("p0"))
        assertEquals(40.0, summary.firstThrottleSeconds.getValue("p4")!!, 0.11)
        assertNull(summary.firstThrottleSeconds.getValue("p7"))
        assertEquals(0.8, summary.cpuStability!!, 0.01)
        assertEquals(0.8, summary.gpuStability!!, 0.01)
        assertEquals(40.0 + 179.9 / 10, summary.maxTemperatures.getValue("A715"), 1e-6)
        assertEquals(40.0, summary.startTemperatures.getValue("A715"), 1e-9)
        assertEquals(30.0, summary.maxTemperatures.getValue(RunAnalysis.BATTERY), 1e-9)
        assertEquals(0L, summary.computationErrors)
    }

    @Test
    fun `series hold one point a second`() {
        val (_, series) = analyze()
        assertEquals(180, series.seconds.size)
        assertEquals(9.0, series.watts[30]!!, 1e-9)
        assertEquals(7.0, series.watts[120]!!, 1e-9)
        assertEquals(1.0, series.cpuRelative[30]!!, 0.02)
        assertEquals(0.8, series.cpuRelative[120]!!, 0.02)
        assertEquals(1920.0, series.clocksMhz.getValue("p4")[100]!!, 1e-9)
        assertEquals(setOf("A715", "A510", "GPU", "DDR"), series.temperatures.keys)
        assertNull(series.cpuRelative.last())
    }

    @Test
    fun `idle clusters are not reported as throttled`() {
        val (baseline, load) = run()
        val gpuOnly = load.map { it.copy(cpu = TestSamples.snapshot(null, 0)) }
        val summary = RunAnalysis.analyze(baseline, gpuOnly, TestSamples.clusters) { "p${it.policy}" }.first
        assertTrue(summary.firstThrottleSeconds.isEmpty())
    }

    @Test
    fun `a charger anywhere voids the power figures`() {
        assertFalse(analyze(plugged = true).first.powerValid)
    }
}
