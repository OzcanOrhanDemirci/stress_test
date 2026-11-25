package dev.ozcan.stress.device

import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.SysfsLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocNamesTest {
    @Test
    fun `known vendor IDs map to the right family`() {
        val fixtures = mapOf(
            "SM7550" to "Snapdragon 7 Gen 3",
            "SM8750" to "Snapdragon 8 Elite",
            "SM8750-AC" to "Snapdragon 8 Elite",
            "MT6989" to "Dimensity 9300",
            "MT6991" to "Dimensity 9400",
            "s5e9945" to "Exynos 2400",
            "gs101" to "Google Tensor",
            "gs201" to "Google Tensor G2",
            "zuma" to "Google Tensor G3",
            "zumapro" to "Google Tensor G4",
            "hi3670" to "Kirin 970",
        )
        for ((part, name) in fixtures) assertEquals("$name ($part)", SocNames.display(null, part))
        assertEquals("Unisoc ums9230", SocNames.display("Spreadtrum", "ums9230"))
        assertEquals("Unisoc SC9863A", SocNames.display(null, "SC9863A"))
    }

    @Test
    fun `a real model wins while generic hardware uses the most specific readable identifier`() {
        assertEquals("Snapdragon 8 Elite (SM8750)", SocNames.resolve("Qualcomm", "SM8750", "qcom", "other") { error("unused") })
        assertEquals("Google Tensor G2 (gs201)", SocNames.resolve(null, "unknown", "gs201", null) { null })
        assertEquals("Snapdragon 8 Elite (sun)", SocNames.resolve("Qualcomm", "qcom", "qcom", "mantaray") { "sun" })
        assertEquals("MediaTek MT8000", SocNames.resolve("MediaTek", " MT8000 ", "mtk", null) { error("unused") })
        assertEquals("Exynos 2600", SocNames.display("Exynos", "Exynos 2600"))
        assertNull(SocNames.resolve(null, "unknown", "qcom", "unknown") { "" })
    }

    @Test
    fun `the device can identify cores without a cpufreq policy or top clock`() {
        val layout = SysfsLayout(
            clusters = listOf(CpuCluster(0, listOf(0, 1), 0, "p0", "Cortex-A55")),
            zones = emptyList(),
            gpuBusyPath = null,
            cpuCount = 4,
            cpuNames = mapOf(0 to "Cortex-A55", 1 to "Cortex-A55", 2 to "Cortex-A76", 3 to "Cortex-A76"),
        )
        assertEquals(listOf("Cortex-A55 ×2", "Cortex-A76 ×2"), DeviceInfo.cpuLabels(layout))
    }
}
