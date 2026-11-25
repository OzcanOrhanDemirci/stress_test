package dev.ozcan.stress.run

import dev.ozcan.stress.TestSamples
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockReadingTest {
    @Test
    fun `a policy gap cannot be counted as three seconds of throttling`() {
        val load = (0..8).map { t ->
            TestSamples.sample(t.toDouble(), cpu = TestSamples.snapshot(TestSamples.gemm, t.toLong()),
                freqKhz = listOf(1_804_800L, if (t in 2..5) null else 1_900_000L, null))
        }
        val (summary, series) = RunAnalysis.analyze(emptyList(), load, TestSamples.clusters, CpuCluster::label)
        assertTrue(summary.firstThrottleSeconds.isEmpty())
        assertFalse("CPU 7" in series.clocksMhz)
    }

    @Test
    fun `equal named policies retain separate chart lines and throttle findings`() {
        val clusters = listOf(
            CpuCluster(0, (0..3).toList(), 2_200_000, "p0", "Cortex-A55"),
            CpuCluster(4, (4..7).toList(), 2_200_000, "p4", "Cortex-A55"),
        )
        val load = (0..8).map { t ->
            TestSamples.sample(t.toDouble(), cpu = TestSamples.snapshot(TestSamples.gemm, t.toLong()),
                freqKhz = listOf(2_200_000L, 1_900_000L))
        }
        val (summary, series) = RunAnalysis.analyze(emptyList(), load, clusters, CpuCluster::label)
        assertEquals(setOf("Cortex-A55 ×4 (CPU 0-3)", "Cortex-A55 ×4 (CPU 4-7)"), series.clocksMhz.keys)
        assertEquals(0.0, summary.firstThrottleSeconds.getValue("Cortex-A55 ×4 (CPU 4-7)")!!, 0.0)
    }
}
