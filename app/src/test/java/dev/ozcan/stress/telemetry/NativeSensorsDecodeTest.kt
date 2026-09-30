package dev.ozcan.stress.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSensorsDecodeTest {

    private val missing = Long.MIN_VALUE

    private val layout = SysfsLayout(
        clusters = listOf(
            CpuCluster(0, listOf(0, 1, 2, 3), 1_804_800, "p0"),
            CpuCluster(7, listOf(7), 2_630_400, "p7"),
        ),
        zones = listOf(
            ThermalZone("cpu-1-0", ThermalGroup.BigCores, "z0"),
            ThermalZone("cpu-1-1", ThermalGroup.BigCores, "z1"),
            ThermalZone("gpuss-0", ThermalGroup.Gpu, "z2"),
            ThermalZone("ddr", ThermalGroup.Memory, "z3"),
        ),
        gpuBusyPath = "g",
    )

    @Test
    fun `decodes clocks, hottest zone per group and the gpu counter`() {
        val values = longArrayOf(
            1_804_800, missing, // p0
            missing, missing, // p7 unreadable
            41_200, missing, // z0
            44_900, missing, // z1: hotter big core wins
            -273_000, missing, // z2: placeholder, dropped
            38_000, missing, // z3
            302_334, 1_000_061, // gpubusy
        )
        val r = NativeSensors.decode(layout, values)
        assertEquals(listOf(1_804_800L, null), r.clusterFreqKhz)
        assertEquals(mapOf(ThermalGroup.BigCores to 44.9, ThermalGroup.Memory to 38.0), r.temperatures)
        assertEquals(GpuBusy(302_334, 1_000_061), r.gpuBusy)
        assertEquals(0.3023, r.gpuBusy!!.fraction!!, 1e-4)
    }

    @Test
    fun `gpu busy needs both numbers and a sane ratio`() {
        val values = LongArray(14) { missing }.also { it[12] = 5 }
        assertNull(NativeSensors.decode(layout, values).gpuBusy)
        assertNull(GpuBusy(10, 5).fraction)
        assertNull(GpuBusy(0, 0).fraction)
    }
}
