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
            ThermalZone("cpu-1-0", ThermalGroup.Cpu, "z0"),
            ThermalZone("cpu-1-1", ThermalGroup.Cpu, "z1"),
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
            44_900, missing, // z1: hotter core wins
            -273_000, missing, // z2: placeholder, dropped
            38_000, missing, // z3
            302_334, 1_000_061, // gpubusy
        )
        val r = NativeSensors.decode(layout, values)
        assertEquals(listOf(1_804_800L, null), r.clusterFreqKhz)
        assertEquals(mapOf(ThermalGroup.Cpu to 44.9, ThermalGroup.Memory to 38.0), r.temperatures)
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

    @Test
    fun `a percentage file reads as out of a hundred`() {
        val percent = layout.copy(gpuBusyFormat = GpuBusyFormat.Percent)
        val values = LongArray(14) { missing }.also { it[12] = 37 }
        assertEquals(0.37, NativeSensors.decode(percent, values).gpuBusy!!.fraction!!, 1e-9)
        // MediaTek's "loading idle block": the first number is the load.
        val mediatek = LongArray(14) { missing }.also { it[12] = 35; it[13] = 65 }
        assertEquals(0.35, NativeSensors.decode(percent, mediatek).gpuBusy!!.fraction!!, 1e-9)
    }

    @Test
    fun `no gpu file means no gpu reading`() {
        val none = layout.copy(gpuBusyPath = null)
        assertNull(NativeSensors.decode(none, LongArray(12) { 1 }).gpuBusy)
    }

    @Test
    fun `zone readings in degrees, tenths and thousandths all become celsius`() {
        assertEquals(45.0, NativeSensors.celsius(45)!!, 1e-9)
        assertEquals(45.5, NativeSensors.celsius(455)!!, 1e-9)
        assertEquals(45.5, NativeSensors.celsius(45_500)!!, 1e-9)
        assertEquals(-5.0, NativeSensors.celsius(-5_000)!!, 1e-9)
        assertNull(NativeSensors.celsius(0))
        assertNull(NativeSensors.celsius(-273_000))
        assertNull(NativeSensors.celsius(400_000))
    }
}
