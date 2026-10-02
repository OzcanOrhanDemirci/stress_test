package dev.ozcan.stress.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuPartsTest {

    @Test
    fun `the identification register names the core`() {
        // The Honor 400's A510 and A715, read from the phone on 2026-10-02.
        assertEquals(0x41 to 0xd46, CpuParts.parseMidr("0x00000000411fd462\n"))
        assertEquals("Cortex-A510", CpuParts.name(0x41, 0xd46))
        assertEquals("Cortex-A715", CpuParts.parseMidr("0x00000000411fd4d1")?.let { (i, p) -> CpuParts.name(i, p) })
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
        assertEquals(mapOf(0 to (0x41 to 0xd05), 6 to (0x41 to 0xd0d)), CpuParts.parseCpuinfo(text))
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
    }
}
