package dev.ozcan.stress.lab

import dev.ozcan.stress.TestSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LabLoadTest {

    private fun parse(text: String) = LabLoad.parse(text, TestSamples.kernels, TestSamples.burners)

    @Test
    fun `cpu only, gpu only and both`() {
        assertNull(parse("fp32_gemm").gpu)
        assertNull(parse("gpu_fp32").cpu)
        assertEquals(GpuPart(null), parse("scene").gpu)
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
    fun `malformed workloads are rejected`() {
        for (bad in listOf("", "+", "gpu_fp32+gpu_blend", "scene+gpu_fp32", "dry+fp32_gemm", "dry++gpu_fp32", "nope")) {
            assertThrows(bad, IllegalArgumentException::class.java) { parse(bad) }
        }
    }
}
