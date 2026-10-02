package dev.ozcan.stress.lab

import dev.ozcan.stress.TestSamples
import dev.ozcan.stress.TestSamples.sample
import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.GpuPart
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.telemetry.GpuBusy
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabAnalysisTest {

    private val workload = Workload(CoreAssignment.uniform(TestSamples.gemm, TestSamples.CPU_COUNT), GpuPart(TestSamples.gpuFp32))

    private val spec = LabSpec(
        loads = listOf(workload),
        repeat = 1,
        idleSeconds = 10,
        loadSeconds = 60,
        nice = 0,
        batchMillis = 20,
        brightness = 0.2f,
        coolCelsius = 40.0,
        waitForBattery = true,
    )

    private val context = RunContext(workload, index = 2, count = 5, mapOf(ThermalGroup.Cpu to 39.5), cooledInTime = true)

    /**
     * 10 s idle at 0.5 A then 60 s of load at 2 A, both at 4.0 V, sampled at
     * 10 Hz. The charge counter falls consistently with the current; each CPU
     * finishes 50 batches of 10 iterations per second.
     */
    private fun run(errors: Long = 0, gpuErrors: Long = 0, plugged: Boolean = false): Pair<List<Sample>, List<Sample>> {
        var counter = 4_000_000.0
        var t = 0.0
        val idle = (0 until 100).map {
            sample(t, currentRaw = -500_000, chargeCounter = counter.toLong(), cpu = TestSamples.snapshot(null, 0))
                .also { t += 0.1; counter -= 0.5e6 * 0.1 / 3600 }
        }
        val load = (0 until 600).map { i ->
            val cpu = TestSamples.snapshot(TestSamples.gemm, batches = i * 5L, errors = errors)
            val gpu = TestSamples.gpu(TestSamples.gpuFp32, frames = i / 4L, errors = gpuErrors)
            val temps = mapOf(ThermalGroup.Cpu to 40.0 + i / 20.0)
            sample(
                t, currentRaw = -2_000_000, chargeCounter = counter.toLong(), cpu = cpu, temperatures = temps,
                plugged = plugged && i == 300, gpu = gpu, gpuBusy = GpuBusy(980, 1000),
            )
                .also { t += 0.1; counter -= 2e6 * 0.1 / 3600 }
        }
        return idle to load
    }

    @Test
    fun `power, baseline and cross-check come out of the samples`() {
        val (idle, load) = run()
        val r = LabAnalysis.analyze(spec, context, LabRunner.STARTED, idle, load, TestSamples.clusters)

        assertEquals(1e-6, r.ampsPerUnit!!, 0.0)
        assertEquals(-1, r.dischargeSign)
        assertEquals(2.0, r.idle.meanWatts!!, 1e-9)
        assertEquals(8.0, r.load.meanWatts!!, 1e-9)
        assertEquals(6.0, r.loadAboveIdleWatts!!, 1e-9)
        assertEquals(8.0, r.loadFirst30sWatts!!, 1e-9)
        assertEquals(8.0, r.loadLast30sWatts!!, 1e-9)
        assertEquals(8.0, r.loadMax5sWatts!!, 1e-9)
        assertEquals(2.0, r.load.meanDischargeAmps!!, 1e-9)
        assertEquals(2.0, r.load.chargeCounterAmps!!, 0.02)
        assertEquals(0.5, r.idle.chargeCounterAmps!!, 0.02)
        assertFalse(r.pluggedDuringRun)
        assertEquals(0.1, r.cadence.sampleIntervalSeconds!!, 1e-6)
        assertEquals("fp32_gemm+gpu_fp32", r.workload)
        assertEquals(2, r.runIndex)
        assertEquals(5, r.runCount)
        assertEquals(mapOf("cpu" to 39.5), r.startTemperatures)
    }

    @Test
    fun `cpu rates, clocks and temperatures`() {
        val (idle, load) = run()
        val r = LabAnalysis.analyze(spec, context, LabRunner.STARTED, idle, load, TestSamples.clusters)

        // 5 batches per sample (0.1 s) * 10 iterations * 1000 FLOP = 500_000 FLOP/s.
        r.cpus.forEach { cpu ->
            assertEquals("C2", cpu.kernel)
            assertEquals(500_000.0, cpu.meanRate!!, 1.0)
            assertEquals(500_000.0, cpu.first10sRate!!, 1.0)
            assertEquals(500_000.0, cpu.last10sRate!!, 1.0)
            assertTrue(cpu.pinned)
        }
        listOf(1804.8, 2400.0, 2630.4).zip(r.clusters) { expected, cluster ->
            assertEquals(expected, cluster.meanMhz!!, 1e-6)
        }
        assertEquals(40.0 + 599 / 20.0, r.maxTemperatures.getValue("cpu"), 1e-9)
        assertEquals(0L, r.computationErrors)
    }

    @Test
    fun `gpu rate, frame time and busy come out of the samples`() {
        val (idle, load) = run()
        val g = LabAnalysis.analyze(spec, context, LabRunner.STARTED, idle, load, TestSamples.clusters).gpu!!
        // A frame every 0.4 s (one per four samples) of 1_000_000 work units, 40 ms of GPU time each.
        assertEquals("G1", g.burner)
        assertEquals(2.5e6, g.meanRate!!, 5e4)
        assertEquals(2.5, g.framesPerSecond!!, 0.05)
        assertEquals(40.0, g.meanFrameMillis!!, 1e-6)
        assertEquals(0.98, g.busyFraction!!, 1e-9)
        assertEquals(10L, g.dispatchesPerFrame)
        assertEquals("Running", g.finalState)
    }

    @Test
    fun `errors and a charger are reported, and a charger voids the counter check`() {
        val (idle, load) = run(errors = 3, gpuErrors = 2, plugged = true)
        val r = LabAnalysis.analyze(spec, context, LabRunner.STARTED, idle, load, TestSamples.clusters)
        assertEquals(26L, r.computationErrors) // 3 per CPU on 8 CPUs, plus 2 on the GPU
        assertTrue(r.pluggedDuringRun)
        assertEquals(null, r.load.chargeCounterAmps)
    }

    @Test
    fun `a load that never started has an empty load phase`() {
        val (idle, _) = run()
        val r = LabAnalysis.analyze(spec, context, "cpu=NoMemory", idle, emptyList(), TestSamples.clusters)
        assertEquals("cpu=NoMemory", r.startResult)
        assertEquals(null, r.gpu)
        assertEquals(0, r.load.samples)
        assertEquals(null, r.load.meanWatts)
        assertTrue(r.cpus.isEmpty())
    }
}
