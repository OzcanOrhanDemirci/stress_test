package dev.ozcan.stress.engine

import dev.ozcan.stress.TestSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkloadTest {

    private fun parse(text: String) = Workload.parse(text, TestSamples.kernels, TestSamples.burners, TestSamples.CPU_COUNT)

    @Test
    fun `cpu only, gpu only and both`() {
        assertNull(parse("fp32_gemm").gpu)
        assertNull(parse("gpu_fp32").cpu)
        assertEquals(GpuPart(null, scene = true), parse("scene").gpu)
        val both = parse("0-3:dry,4-7:fp32_gemm+gpu_blend")
        assertEquals(TestSamples.gpuBlend, both.gpu!!.burner)
        assertEquals("0-3:dry,4-7:fp32_gemm", both.cpu!!.describe())
    }

    @Test
    fun `describe round-trips and puts the cpu part first`() {
        for (text in listOf("fp32_gemm", "gpu_fp32", "scene", "dry+gpu_blend", "dry+scene")) {
            assertEquals(text, parse(text).describe())
        }
        assertEquals("dry+gpu_blend", parse("gpu_blend+dry").describe())
    }

    @Test
    fun `gpu parts take a preview or scale option`() {
        assertEquals(GpuPart(TestSamples.gpuFp32, scene = false), parse("gpu_fp32@preview").gpu)
        assertEquals(GpuPart(null, scene = true, sceneScalePercent = 35), parse("scene@35").gpu)
        for (text in listOf("gpu_fp32@preview", "scene@35", "dry+gpu_blend@50")) assertEquals(text, parse(text).describe())
    }

    @Test
    fun `the forest is a scene of its own`() {
        assertEquals(GpuPart(null, scene = true, kind = SceneKind.Forest), parse("forest").gpu)
        assertEquals(GpuPart(null, scene = true, sceneScalePercent = 45, kind = SceneKind.Forest), parse("forest@45").gpu)
        for (text in listOf("forest", "forest@45", "dry+forest")) assertEquals(text, parse(text).describe())
        assertThrows(IllegalArgumentException::class.java) { parse("forest@preview") }
    }

    @Test
    fun `malformed workloads are rejected`() {
        val bad = listOf(
            "", "+", "gpu_fp32+gpu_blend", "scene+gpu_fp32", "dry+fp32_gemm", "dry++gpu_fp32", "nope",
            "gpu_fp32@", "gpu_fp32@fast", "scene@5", "scene@101",
        )
        for (bad in bad) {
            assertThrows(bad, IllegalArgumentException::class.java) { parse(bad) }
        }
    }
}
