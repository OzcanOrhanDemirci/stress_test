package dev.ozcan.stress.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SysfsLayoutTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(path: String, text: String) {
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText(text) }
    }

    /** The Honor 400's layout as read over adb on 2026-09-30 and 2026-10-02, trimmed. */
    private fun honor400() {
        write("sys/devices/system/cpu/possible", "0-7\n")
        write("sys/devices/system/cpu/cpufreq/policy0/related_cpus", "0 1 2 3\n")
        write("sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq", "1804800\n")
        write("sys/devices/system/cpu/cpufreq/policy4/related_cpus", "4 5 6\n")
        write("sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq", "2400000\n")
        write("sys/devices/system/cpu/cpufreq/policy7/related_cpus", "7\n")
        write("sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq", "2630400\n")
        write("sys/devices/system/cpu/cpu0/regs/identification/midr_el1", "0x00000000411fd462\n")
        write("sys/devices/system/cpu/cpu4/regs/identification/midr_el1", "0x00000000411fd4d1\n")
        write("sys/devices/system/cpu/cpu7/regs/identification/midr_el1", "0x00000000411fd4d1\n")
        write("sys/class/kgsl/kgsl-3d0/gpubusy", "0 0\n")
        val zones = listOf(
            "pa", "cpu-1-0", "cpu-1-7", "cpu-0-0", "cpuss-0", "ddr", "gpuss-0", "nspss-2", "mdmss-1", "modem_cfg",
            "camera-0", "epm0", "pm7550ba-ibat-lvl0", "vbat", "socd", "shell_front", "battery", "usb_port",
        )
        zones.forEachIndexed { i, type -> write("sys/class/thermal/thermal_zone$i/type", "$type\n") }
    }

    @Test
    fun `discovers clusters in policy order, with their cores`() {
        honor400()
        val layout = SysfsLayout.discover(tmp.root)
        assertEquals(listOf(0, 4, 7), layout.clusters.map { it.policy })
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6), listOf(7)), layout.clusters.map { it.cpus })
        assertEquals(2_630_400L, layout.clusters[2].maxFreqKhz)
        assertEquals(listOf("Cortex-A510", "Cortex-A715", "Cortex-A715"), layout.clusters.map { it.coreName })
        assertEquals(8, layout.cpuCount)
        assertTrue(layout.clusters[0].currentFreqPath.endsWith("policy0${File.separator}scaling_cur_freq"))
    }

    @Test
    fun `keeps the zones of known groups and leaves out the ones that are not temperatures`() {
        honor400()
        val zones = SysfsLayout.discover(tmp.root).zones
        assertEquals(
            listOf(
                "cpu-1-0" to ThermalGroup.Cpu,
                "cpu-1-7" to ThermalGroup.Cpu,
                "cpu-0-0" to ThermalGroup.Cpu,
                "cpuss-0" to ThermalGroup.Cpu,
                "ddr" to ThermalGroup.Memory,
                "gpuss-0" to ThermalGroup.Gpu,
                "nspss-2" to ThermalGroup.Npu,
                "mdmss-1" to ThermalGroup.Modem,
                "camera-0" to ThermalGroup.Camera,
                "shell_front" to ThermalGroup.Skin,
            ),
            zones.map { it.type to it.group },
        )
    }

    @Test
    fun `paths list clocks, then zones, then the gpu counter`() {
        honor400()
        val layout = SysfsLayout.discover(tmp.root)
        val paths = layout.paths
        assertEquals(3 + layout.zones.size + 1, paths.size)
        assertTrue(paths.last().endsWith("gpubusy"))
        assertEquals(GpuBusyFormat.Pair, layout.gpuBusyFormat)
    }

    @Test
    fun `an unreadable tree gives an empty layout, not an error`() {
        val layout = SysfsLayout.discover(File(tmp.root, "missing"))
        assertTrue(layout.clusters.isEmpty())
        assertTrue(layout.zones.isEmpty())
        assertNull(layout.gpuBusyPath)
        assertTrue(layout.cpuCount >= 1)
    }

    @Test
    fun `other vendors' zone names land in the same groups`() {
        val expected = mapOf(
            // MediaTek
            "mtktscpu" to ThermalGroup.Cpu,
            "cpu-little-core0" to ThermalGroup.Cpu,
            "mtktsAP" to null,
            "gpu1" to ThermalGroup.Gpu,
            "apu" to ThermalGroup.Npu,
            // Samsung Exynos and Google Tensor
            "BIG" to ThermalGroup.Cpu,
            "MID" to ThermalGroup.Cpu,
            "LITTLE" to ThermalGroup.Cpu,
            "G3D" to ThermalGroup.Gpu,
            "ISP" to ThermalGroup.Camera,
            "NPU" to ThermalGroup.Npu,
            "TPU" to ThermalGroup.Npu,
            // Unisoc
            "cputop-thmzone" to ThermalGroup.Cpu,
            "gpu-thmzone" to ThermalGroup.Gpu,
            // Qualcomm extras
            "skin-msm-therm" to ThermalGroup.Skin,
            "xo-therm" to ThermalGroup.Skin,
            "quiet-therm" to ThermalGroup.Skin,
            "lpddr" to ThermalGroup.Memory,
            // Not temperatures, or not the chip's.
            "display" to null,
            "battery" to null,
            "bcl_warn" to null,
            "pm8550-bcl-lvl0" to null,
            "charger" to null,
            "" to null,
        )
        for ((type, group) in expected) assertEquals(type, group, ThermalGroup.of(type))
    }

    @Test
    fun `the gpu load is found where each vendor keeps it`() {
        write("sys/kernel/ged/hal/gpu_utilization", "35 65 0\n")
        val mediatek = SysfsLayout.discover(tmp.root)
        assertTrue(mediatek.gpuBusyPath!!.endsWith("gpu_utilization"))
        assertEquals(GpuBusyFormat.Percent, mediatek.gpuBusyFormat)

        val mali = tmp.newFolder("mali")
        File(mali, "sys/devices/platform/1f000000.mali").mkdirs()
        File(mali, "sys/devices/platform/1f000000.mali/utilization").writeText("42\n")
        val tensor = SysfsLayout.discover(mali)
        assertTrue(tensor.gpuBusyPath!!.endsWith("utilization"))
        assertEquals(GpuBusyFormat.Percent, tensor.gpuBusyFormat)
    }

    @Test
    fun `cpu lists read ranges and singles`() {
        assertEquals(listOf(0, 1, 2, 3, 6, 8, 9), SysfsLayout.parseCpuList("0-3,6,8-9\n"))
        assertEquals(listOf(4, 5, 6), SysfsLayout.parseCpuList("4 5 6"))
        assertEquals(emptyList<Int>(), SysfsLayout.parseCpuList("x"))
    }
}
