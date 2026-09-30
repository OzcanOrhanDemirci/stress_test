package dev.ozcan.stress.lab

import dev.ozcan.stress.TestSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabSpecTest {

    private fun parse(vararg pairs: Pair<String, String?>) = LabSpec.parse(mapOf(*pairs), TestSamples.kernels)

    @Test
    fun `a launch without lab keys is not a lab run`() {
        assertNull(LabSpec.parse(mapOf("other" to "x"), TestSamples.kernels))
    }

    @Test
    fun `defaults fill everything but the load`() {
        val spec = parse("lab.load" to "fp32_gemm")!!.getOrThrow()
        assertEquals(10, spec.idleSeconds)
        assertEquals(60, spec.loadSeconds)
        assertEquals(0, spec.nice)
        assertEquals(20, spec.batchMillis)
        assertEquals(LabSpec.DEFAULT_BRIGHTNESS, spec.brightness)
        assertEquals("fp32_gemm", spec.tag)
    }

    @Test
    fun `every field can be set`() {
        val spec = parse(
            "lab.load" to "0-3:dry,4-7:fp32_gemm",
            "lab.idle" to "15",
            "lab.seconds" to " 120 ",
            "lab.nice" to "-5",
            "lab.batch" to "50",
            "lab.brightness" to "0.5",
            "lab.tag" to "karisik",
        )!!.getOrThrow()
        assertEquals(15, spec.idleSeconds)
        assertEquals(120, spec.loadSeconds)
        assertEquals(-5, spec.nice)
        assertEquals(50, spec.batchMillis)
        assertEquals(0.5f, spec.brightness)
        assertEquals("karisik", spec.tag)
        assertEquals("0-3:dry,4-7:fp32_gemm", spec.assignment.describe())
    }

    @Test
    fun `bad values fail with a message instead of running`() {
        for (bad in listOf(
            arrayOf("lab.seconds" to "60"),
            arrayOf("lab.load" to "fp32_gemm", "lab.seconds" to "abc"),
            arrayOf("lab.load" to "fp32_gemm", "lab.seconds" to "1"),
            arrayOf("lab.load" to "fp32_gemm", "lab.brightness" to "2"),
            arrayOf("lab.load" to "unknown"),
        )) {
            val result = parse(*bad)!!
            assertTrue(bad.contentToString(), result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.isNotBlank())
        }
    }
}
