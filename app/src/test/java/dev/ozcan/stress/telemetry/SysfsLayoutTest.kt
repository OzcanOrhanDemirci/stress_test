package dev.ozcan.stress.telemetry

import org.junit.Assert.assertEquals
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

    /** The Honor 400's layout as read over adb on 2026-09-30, trimmed. */
    private fun honor400() {
        write("sys/devices/system/cpu/cpufreq/policy0/related_cpus", "0 1 2 3\n")
        write("sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq", "1804800\n")
        write("sys/devices/system/cpu/cpufreq/policy4/related_cpus", "4 5 6\n")
        write("sys/devices/system/cpu/cpufreq/policy4/cpuinfo_max_freq", "2400000\n")
        write("sys/devices/system/cpu/cpufreq/policy7/related_cpus", "7\n")
        write("sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq", "2630400\n")
        val zones = listOf("pa", "cpu-1-0", "cpu-1-7", "cpu-0-0", "ddr", "ddr_extra", "gpuss-0", "nspss-2", "mdmss-1", "camera-0", "epm0")
        zones.forEachIndexed { i, type -> write("sys/class/thermal/thermal_zone$i/type", "$type\n") }
    }

    @Test
    fun `discovers clusters in policy order`() {
        honor400()
        val layout = SysfsLayout.discover(tmp.root)
        assertEquals(listOf(0, 4, 7), layout.clusters.map { it.policy })
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6), listOf(7)), layout.clusters.map { it.cpus })
        assertEquals(2_630_400L, layout.clusters[2].maxFreqKhz)
        assertTrue(layout.clusters[0].currentFreqPath.endsWith("policy0${File.separator}scaling_cur_freq"))
    }

    @Test
    fun `keeps only zones of known groups, with exact matches for plain names`() {
        honor400()
        val zones = SysfsLayout.discover(tmp.root).zones
        assertEquals(
            listOf(
                "cpu-1-0" to ThermalGroup.BigCores,
                "cpu-1-7" to ThermalGroup.BigCores,
                "cpu-0-0" to ThermalGroup.LittleCores,
                "ddr" to ThermalGroup.Memory,
                "gpuss-0" to ThermalGroup.Gpu,
                "nspss-2" to ThermalGroup.Npu,
                "mdmss-1" to ThermalGroup.Modem,
                "camera-0" to ThermalGroup.Camera,
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
    }

    @Test
    fun `an unreadable tree gives an empty layout, not an error`() {
        val layout = SysfsLayout.discover(File(tmp.root, "missing"))
        assertTrue(layout.clusters.isEmpty())
        assertTrue(layout.zones.isEmpty())
    }
}
