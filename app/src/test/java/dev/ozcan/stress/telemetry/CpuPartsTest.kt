package dev.ozcan.stress.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuPartsTest {

    @Test
    fun `the identification register names the core`() {
        // The Honor 400's A510 and A715, read from the phone on 2026-10-02.
        assertEquals(CpuParts.CoreId(0x41, 0xd46, 1), CpuParts.parseMidr("0x00000000411fd462\n"))
        assertEquals("Cortex-A510", CpuParts.name(0x41, 0xd46))
        assertEquals("Cortex-A715", CpuParts.parseMidr("0x00000000411fd4d1")?.name())
        assertEquals("Cortex-X4", CpuParts.name(0x41, 0xd82))
        assertEquals("Oryon", CpuParts.name(0x51, 0x001))
        assertNull(CpuParts.name(0x41, 0xfff))
        assertNull(CpuParts.parseMidr("garbage"))
        assertNull(CpuParts.parseMidr("0x0"))
    }

    @Test
    fun `proc cpuinfo gives the same when the register is hidden`() {
        val text = """
            processor	: 0
            BogoMIPS	: 38.40
            CPU implementer	: 0x41
            CPU part	: 0xd05

            processor	: 6
            CPU implementer	: 0x41
            CPU part	: 0xd0d
        """.trimIndent()
        assertEquals(mapOf(0 to CpuParts.CoreId(0x41, 0xd05), 6 to CpuParts.CoreId(0x41, 0xd0d)), CpuParts.parseCpuinfo(text))
    }

    @Test
    fun `clusters take roles by their top clock`() {
        fun cluster(policy: Int, cpus: Int, khz: Long) = CpuCluster(policy, List(cpus) { policy + it }, khz, "p$policy")
        assertEquals(listOf(ClusterRole.All), ClusterRole.of(listOf(cluster(0, 8, 2_000_000))))
        assertEquals(
            listOf(ClusterRole.Little, ClusterRole.Big),
            ClusterRole.of(listOf(cluster(0, 6, 1_800_000), cluster(6, 2, 2_200_000))),
        )
        // The Honor 400: 4 + 3 + 1.
        assertEquals(
            listOf(ClusterRole.Little, ClusterRole.Big, ClusterRole.Prime),
            ClusterRole.of(listOf(cluster(0, 4, 1_804_800), cluster(4, 3, 2_400_000), cluster(7, 1, 2_630_400))),
        )
        // Three clusters without a lone top core: little, mid, big.
        assertEquals(
            listOf(ClusterRole.Little, ClusterRole.Mid, ClusterRole.Big),
            ClusterRole.of(listOf(cluster(0, 2, 1_800_000), cluster(2, 3, 2_500_000), cluster(5, 3, 3_000_000))),
        )
        // Four clusters: little, mid, big, prime.
        assertEquals(
            listOf(ClusterRole.Little, ClusterRole.Mid, ClusterRole.Big, ClusterRole.Prime),
            ClusterRole.of(
                listOf(cluster(0, 2, 2_000_000), cluster(2, 3, 2_600_000), cluster(5, 2, 2_900_000), cluster(7, 1, 3_300_000)),
            ),
        )
    }

    @Test
    fun `a cluster's label is its core and count, or its CPUs`() {
        assertEquals("Cortex-A715 ×3", CpuCluster(4, listOf(4, 5, 6), 2_400_000, "p4", "Cortex-A715").label())
        assertEquals("CPU 4-6", CpuCluster(4, listOf(4, 5, 6), 2_400_000, "p4").label())
        assertEquals("CPU 7", CpuCluster(7, listOf(7), 2_630_400, "p7").label())
        assertEquals("CPU 1,3", CpuCluster(1, listOf(1, 3), 2_000_000, "p1").label())
    }

    @Test
    fun `vendor IDs and Samsung variants identify the core without borrowing Arm part names`() {
        val expected = mapOf(
            "0x531f0011" to "Mongoose M1",
            "0x534f0010" to "Mongoose M2",
            "0x531f0020" to "Mongoose M3",
            "0x531f0030" to "Mongoose M4",
            "0x531f0040" to "Mongoose M5",
            "0x510f0010" to "Oryon",
            "0x510f0020" to "Oryon",
            "0x480fd010" to "TaiShan v110",
            "0x410fd800" to "Cortex-A520",
            "0x410fd810" to "Cortex-A720",
            "0x410fd870" to "Cortex-A725",
            "0x410fd850" to "Cortex-X925",
        )
        for ((midr, name) in expected) assertEquals(midr, name, CpuParts.parseMidr(midr)?.name())
        assertNull(CpuParts.name(0x51, 0xd82))
        assertNull(CpuParts.name(0x41, 0xd84))
    }

    @Test
    fun `core types keep all-big and all-A55 layouts from inventing performance classes`() {
        fun cluster(first: Int, count: Int, mhz: Long, name: String) =
            CpuCluster(first, List(count) { first + it }, mhz * 1000, "p$first", name)
        val a55 = cluster(0, 4, 1800, "Cortex-A55")
        val a510 = cluster(0, 4, 1850, "Cortex-A510")
        val a520 = cluster(0, 4, 1950, "Cortex-A520")
        val fixtures = listOf(
            // Tensor G1/G2, G3, G4; Dimensity 9300/9400; Snapdragon 8 Elite; Exynos 2400; Unisoc A55.
            listOf(a55, cluster(4, 2, 2350, "Cortex-A76"), cluster(6, 2, 2850, "Cortex-X1")) to
                listOf(ClusterRole.Little, ClusterRole.Mid, ClusterRole.Big),
            listOf(a510, cluster(4, 4, 2370, "Cortex-A715"), cluster(8, 1, 2910, "Cortex-X3")) to
                listOf(ClusterRole.Little, ClusterRole.Big, ClusterRole.Prime),
            listOf(a520.copy(cpus = listOf(0, 1, 2, 3)), cluster(4, 3, 2600, "Cortex-A720"), cluster(7, 1, 3100, "Cortex-X4")) to
                listOf(ClusterRole.Little, ClusterRole.Big, ClusterRole.Prime),
            listOf(cluster(0, 4, 2000, "Cortex-A720"), cluster(4, 3, 2850, "Cortex-X4"), cluster(7, 1, 3250, "Cortex-X4")) to
                listOf(ClusterRole.Mid, ClusterRole.Big, ClusterRole.Prime),
            listOf(cluster(0, 4, 2400, "Cortex-A720"), cluster(4, 3, 3300, "Cortex-X4"), cluster(7, 1, 3620, "Cortex-X925")) to
                listOf(ClusterRole.Mid, ClusterRole.Big, ClusterRole.Prime),
            listOf(cluster(0, 6, 3530, "Oryon"), cluster(6, 2, 4320, "Oryon")) to
                listOf(ClusterRole.Big, ClusterRole.Prime),
            listOf(a520, cluster(4, 2, 2590, "Cortex-A720"), cluster(6, 3, 2890, "Cortex-A720"), cluster(9, 1, 3210, "Cortex-X4")) to
                listOf(ClusterRole.Little, ClusterRole.Mid, ClusterRole.Big, ClusterRole.Prime),
            listOf(a55, cluster(4, 4, 1600, "Cortex-A55")) to listOf(ClusterRole.Little, ClusterRole.Little),
        )
        for ((clusters, roles) in fixtures) assertEquals(roles, ClusterRole.of(clusters))
        assertEquals(listOf(ClusterRole.Unknown), ClusterRole.of(listOf(a55), cpuCount = 8))
    }
}
