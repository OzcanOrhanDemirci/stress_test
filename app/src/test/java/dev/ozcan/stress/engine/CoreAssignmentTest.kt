package dev.ozcan.stress.engine

import dev.ozcan.stress.TestSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAssignmentTest {

    private val kernels = TestSamples.kernels

    @Test
    fun `a single key runs on every cpu`() {
        val a = CoreAssignment.parse("fp32_gemm", kernels)
        assertTrue(a.kernelPerCpu.all { it == TestSamples.gemm })
        assertEquals("fp32_gemm", a.describe())
    }

    @Test
    fun `groups assign ranges and leave the rest idle`() {
        val a = CoreAssignment.parse("0-3:dry, 7:fp32_gemm", kernels)
        assertEquals(List(4) { TestSamples.dry } + listOf(null, null, null, TestSamples.gemm), a.kernelPerCpu)
        assertEquals(intArrayOf(0, 0, 0, 0, -1, -1, -1, 2).toList(), a.toNative().toList())
    }

    @Test
    fun `describe round-trips through parse`() {
        for (text in listOf("0-3:dry,4-7:fp32_gemm", "0:dry,2-3:fp32_gemm,7:dry", "fp32_gemm")) {
            val a = CoreAssignment.parse(text, kernels)
            assertEquals(text, a.describe())
            assertEquals(a, CoreAssignment.parse(a.describe(), kernels))
        }
    }

    @Test
    fun `bad input is rejected with a reason`() {
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment.parse("nope", kernels) }
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment.parse("0-8:dry", kernels) }
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment.parse("3-1:dry", kernels) }
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment.parse("0-3:dry,3:fp32_gemm", kernels) }
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment.parse("", kernels) }
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment.parse("0:dry:x", kernels) }
    }

    @Test
    fun `an all idle assignment is idle`() {
        assertTrue(CoreAssignment.uniform(null).isIdle)
        assertNull(CoreAssignment.uniform(null).kernelPerCpu.first())
    }
}
